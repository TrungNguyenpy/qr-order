package com.qrorder.menu.image

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

/**
 * Cấu hình Cloudinary lấy từ biến môi trường (quy tắc 6, 7): CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY,
 * CLOUDINARY_API_SECRET, CLOUDINARY_FOLDER. Thiếu một trong ba giá trị đầu thì upload trả 503
 * IMAGE_STORAGE_NOT_CONFIGURED, các API khác vẫn hoạt động.
 */
@ConfigurationProperties(prefix = "app.cloudinary")
data class CloudinaryProperties(
    val cloudName: String = "",
    val apiKey: String = "",
    val apiSecret: String = "",
    val folder: String = "qr-order/products",
    val apiBaseUrl: String = "https://api.cloudinary.com",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(30),
) {
    val configured: Boolean get() = cloudName.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank()

    /** Không bao giờ in apiSecret (NFR-04). */
    override fun toString() =
        "CloudinaryProperties(cloudName=$cloudName, apiKey=${if (apiKey.isBlank()) "" else "***"}, apiSecret=***, folder=$folder)"
}

/**
 * Chữ ký request Upload API của Cloudinary (tài liệu "Generating authentication signatures"):
 * lấy mọi tham số trừ `file`, `cloud_name`, `resource_type`, `api_key`; sắp xếp theo tên; nối `k=v` bằng `&`;
 * nối thêm api_secret; băm SHA-1, lấy hex chữ thường.
 */
object CloudinarySigner {
    private val EXCLUDED = setOf("file", "cloud_name", "resource_type", "api_key")

    fun sign(params: Map<String, String>, apiSecret: String): String {
        val toSign = params.filterKeys { it !in EXCLUDED }
            .filterValues { it.isNotEmpty() }
            .toSortedMap()
            .entries.joinToString("&") { (k, v) -> "$k=$v" }
        val digest = MessageDigest.getInstance("SHA-1").digest((toSign + apiSecret).toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Tiện ích URL Cloudinary. */
object CloudinaryUrls {
    private val VERSION_SEGMENT = Regex("^v\\d+$")

    /**
     * Trích public_id từ URL giao ảnh Cloudinary, dùng khi `product.image_public_id` trống (dữ liệu nhập tay/cũ):
     * `https://res.cloudinary.com/<cloud>/image/upload/[<transformations>/]v<version>/<public_id>.<ext>`.
     * Nếu không có đoạn `v<version>` thì coi toàn bộ phần sau `/upload/` là public_id (không có transformation).
     * Trả null nếu không phải URL Cloudinary.
     */
    fun extractPublicId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = try {
            URI(url.trim())
        } catch (ex: Exception) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        if (host != "res.cloudinary.com" && !host.endsWith(".cloudinary.com")) return null
        val path = uri.rawPath ?: return null
        val marker = "/upload/"
        val start = path.indexOf(marker).takeIf { it >= 0 } ?: return null
        val segments = path.substring(start + marker.length).split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val versionIndex = segments.indexOfFirst { VERSION_SEGMENT.matches(it) }
        val idSegments = if (versionIndex >= 0) segments.drop(versionIndex + 1) else segments
        if (idSegments.isEmpty()) return null
        val last = idSegments.last()
        val withoutExt = if (last.contains('.')) last.substringBeforeLast('.') else last
        val joined = (idSegments.dropLast(1) + withoutExt).joinToString("/")
        return URLDecoder.decode(joined, StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
    }
}

/**
 * Lưu ảnh món trên Cloudinary qua Upload API REST (signed upload) - không cần SDK riêng.
 * - Upload: `POST {apiBaseUrl}/v1_1/{cloud}/image/upload` (multipart: file, api_key, timestamp, folder, signature);
 *   dùng `secure_url` (https) và `public_id` của phản hồi.
 * - Xóa: `POST {apiBaseUrl}/v1_1/{cloud}/image/destroy` (public_id, api_key, timestamp, signature);
 *   `result` = `ok` hoặc `not found` đều coi là thành công.
 * Không ghi apiSecret hay chữ ký ra log.
 */
class CloudinaryImageStorageService(
    private val properties: CloudinaryProperties,
    private val restClient: RestClient,
    private val clock: Clock,
) : ImageStorageService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun isConfigured(): Boolean = properties.configured

    override fun upload(content: ByteArray, contentType: String, filename: String): StoredImage {
        requireConfigured()
        val params = linkedMapOf(
            "folder" to properties.folder.trim(),
            "timestamp" to timestamp(),
        ).filterValues { it.isNotEmpty() }
        val body = LinkedMultiValueMap<String, Any>()
        params.forEach { (k, v) -> body.add(k, v) }
        body.add("api_key", properties.apiKey)
        body.add("signature", CloudinarySigner.sign(params, properties.apiSecret))
        body.add("file", filePart(content, contentType, filename))

        val response = call("upload") {
            restClient.post()
                .uri("${baseUrl()}/v1_1/{cloud}/image/upload", properties.cloudName)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(JsonNode::class.java)
        }
        val url = response?.get("secure_url")?.asText()?.takeIf { it.startsWith("https://") }
        val publicId = response?.get("public_id")?.asText()?.takeIf { it.isNotBlank() }
        if (url == null || publicId == null) throw ImageStorageException("Phản hồi upload của Cloudinary thiếu secure_url/public_id")
        return StoredImage(url, publicId)
    }

    override fun delete(publicId: String) {
        requireConfigured()
        val params = mapOf("public_id" to publicId, "timestamp" to timestamp())
        val form = LinkedMultiValueMap<String, String>()
        params.forEach { (k, v) -> form.add(k, v) }
        form.add("api_key", properties.apiKey)
        form.add("signature", CloudinarySigner.sign(params, properties.apiSecret))

        val response = call("destroy") {
            restClient.post()
                .uri("${baseUrl()}/v1_1/{cloud}/image/destroy", properties.cloudName)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode::class.java)
        }
        val result = response?.get("result")?.asText()
        if (result != "ok" && result != "not found") {
            throw ImageStorageException("Cloudinary không xóa được ảnh (result=$result)")
        }
    }

    private fun requireConfigured() {
        if (!properties.configured) throw ImageStorageNotConfiguredException()
    }

    private fun timestamp(): String = (clock.millis() / 1000).toString()

    private fun baseUrl(): String = properties.apiBaseUrl.trimEnd('/')

    private fun filePart(content: ByteArray, contentType: String, filename: String): HttpEntity<ByteArrayResource> {
        val resource = object : ByteArrayResource(content) {
            override fun getFilename(): String = filename
        }
        val headers = HttpHeaders().apply { this.contentType = MediaType.parseMediaType(contentType) }
        return HttpEntity(resource, headers)
    }

    private fun <T> call(operation: String, block: () -> T): T =
        try {
            block()
        } catch (ex: RestClientException) {
            // Thông điệp RestClientException có thể chứa body phản hồi, không chứa secret (secret không gửi đi).
            log.warn("Cloudinary {} thất bại: {}", operation, ex.javaClass.simpleName)
            throw ImageStorageException("Cloudinary $operation thất bại", ex)
        }
}

/** Tạo bean [CloudinaryImageStorageService] với timeout kết nối/đọc theo cấu hình. */
@Configuration
class ImageStorageConfig {
    @Bean
    fun cloudinaryImageStorageService(
        properties: CloudinaryProperties,
        restClientBuilder: RestClient.Builder,
        clock: Clock,
    ): CloudinaryImageStorageService {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(properties.connectTimeout)
            setReadTimeout(properties.readTimeout)
        }
        return CloudinaryImageStorageService(properties, restClientBuilder.clone().requestFactory(requestFactory).build(), clock)
    }
}
