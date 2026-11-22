package com.qrorder.menu.image

import com.qrorder.common.api.ErrorDetail
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

/**
 * Giới hạn ảnh món (không hardcode, quy tắc 6). Mặc định: tối đa 5MB, JPEG/PNG/WebP.
 * `spring.servlet.multipart.max-file-size` dùng cùng biến MENU_IMAGE_MAX_SIZE để Tomcat chặn sớm file quá lớn.
 */
@ConfigurationProperties(prefix = "app.menu.image")
data class MenuImageProperties(
    val maxSize: DataSize = DataSize.ofMegabytes(5),
)

/** Kết quả kiểm tra: định dạng thật của file (suy từ nội dung) và phần mở rộng tương ứng. */
data class ValidatedImage(
    val content: ByteArray,
    val contentType: String,
    val extension: String,
) {
    override fun equals(other: Any?) = other is ValidatedImage && other.contentType == contentType && content.contentEquals(other.content)
    override fun hashCode() = 31 * contentType.hashCode() + content.contentHashCode()
    override fun toString() = "ValidatedImage(contentType=$contentType, size=${content.size})"
}

/**
 * Kiểm tra ảnh upload (thuần, không phụ thuộc Spring để unit test):
 * - Không rỗng, không vượt [maxBytes] -> 413 IMAGE_TOO_LARGE.
 * - Content-Type khai báo thuộc `image/jpeg`, `image/png`, `image/webp` -> ngược lại 415 UNSUPPORTED_IMAGE_TYPE.
 * - Chữ ký nhị phân (magic bytes) khớp đúng loại đã khai báo: chặn file đổi đuôi/giả Content-Type.
 */
object ImageUploadValidator {
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val WEBP = "image/webp"
    val ALLOWED_TYPES: Set<String> = setOf(JPEG, PNG, WEBP)
    private val EXTENSIONS = mapOf(JPEG to "jpg", PNG to "png", WEBP to "webp")

    fun validate(content: ByteArray, declaredContentType: String?, maxBytes: Long): ValidatedImage {
        if (content.isEmpty()) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, details = listOf(ErrorDetail("file", "File ảnh rỗng")))
        }
        if (content.size.toLong() > maxBytes) {
            throw BusinessException(ErrorCode.IMAGE_TOO_LARGE, "Ảnh vượt quá dung lượng cho phép (${humanSize(maxBytes)})")
        }
        val declared = normalize(declaredContentType)
        if (declared == null || declared !in ALLOWED_TYPES) throw BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE)
        val detected = detect(content)
        if (detected != declared) {
            throw BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE, "Nội dung file không khớp định dạng ảnh đã khai báo")
        }
        return ValidatedImage(content, declared, EXTENSIONS.getValue(declared))
    }

    /** `image/JPEG; charset=x` -> `image/jpeg`; `image/jpg` (không chuẩn nhưng phổ biến) coi như JPEG. */
    fun normalize(contentType: String?): String? {
        val base = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return if (base == "image/jpg" || base == "image/pjpeg") JPEG else base
    }

    /** Nhận dạng định dạng theo chữ ký đầu file; null nếu không phải JPEG/PNG/WebP. */
    fun detect(content: ByteArray): String? {
        fun at(i: Int) = if (i < content.size) content[i].toInt() and 0xFF else -1
        fun ascii(from: Int, text: String) = text.indices.all { at(from + it) == text[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> JPEG
            at(0) == 0x89 && ascii(1, "PNG") && at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A -> PNG
            ascii(0, "RIFF") && ascii(8, "WEBP") -> WEBP
            else -> null
        }
    }

    private fun humanSize(bytes: Long): String =
        if (bytes % (1024 * 1024) == 0L) "${bytes / (1024 * 1024)}MB" else "$bytes byte"
}
