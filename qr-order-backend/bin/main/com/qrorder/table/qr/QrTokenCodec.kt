package com.qrorder.table.qr

import com.qrorder.auth.token.TokenHasher
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.table.config.QrProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * qrToken của mã QR bàn (BR-S05, SRS §3.1).
 *
 * Thiết kế (Giai đoạn 3 - E7, BE-20): DB chỉ lưu `token_hash` = SHA-256 hex của qrToken (không lưu token gốc, không
 * tạo migration V4). Để vẫn tải lại được ảnh PNG/PDF sau khi tạo, qrToken được dẫn xuất bằng
 * `HMAC-SHA256(QR_TOKEN_SECRET, "qr-token:v1:" + qr_code.id)` rồi mã hóa Base64 URL-safe (256 bit, không đoán được
 * nếu không có khóa; khác nhau cho mỗi lần sinh/xoay vì id mới). Lộ database KHÔNG lộ token (chỉ có hash); muốn dựng
 * lại token phải có cả khóa bí mật.
 *
 * Khi in lại: dựng lại token rồi so SHA-256 với `token_hash` đã lưu; không khớp (QR seed dev dùng token cố định, hoặc
 * đã đổi khóa) -> 409 QR_TOKEN_NOT_REPRODUCIBLE, cần xoay mã mới. QR đã in vẫn quét được vì join tra theo hash.
 */
@Component
class QrTokenCodec(
    properties: QrProperties,
    private val tokenHasher: TokenHasher,
) {
    private val key: SecretKeySpec? = parseSecret(properties.tokenSecret)
    private val baseUrl: String? = parseBaseUrl(properties.baseUrl)
    private val pathPrefix: String = parsePathPrefix(properties.pathPrefix)

    /** Đủ cấu hình để sinh/in QR. */
    val configured: Boolean get() = key != null && baseUrl != null

    fun requireConfigured() {
        if (!configured) throw BusinessException(ErrorCode.QR_NOT_CONFIGURED)
    }

    /** qrToken của bản ghi qr_code [qrCodeId] (43 ký tự Base64 URL-safe). */
    fun tokenFor(qrCodeId: Long): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key ?: throw BusinessException(ErrorCode.QR_NOT_CONFIGURED))
        val digest = mac.doFinal("$TOKEN_CONTEXT$qrCodeId".toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun hashOf(token: String): String = tokenHasher.sha256Hex(token)

    /** Dựng lại token của [qrCodeId]; null nếu không khớp hash đã lưu (so sánh thời gian hằng). */
    fun reproduce(qrCodeId: Long, storedHash: String): String? {
        val token = tokenFor(qrCodeId)
        val matches = MessageDigest.isEqual(
            hashOf(token).toByteArray(Charsets.US_ASCII),
            storedHash.trim().toByteArray(Charsets.US_ASCII),
        )
        return token.takeIf { matches }
    }

    /** URL in trên QR: `{baseUrl}{pathPrefix}{qrToken}`. */
    fun urlFor(token: String): String = (baseUrl ?: throw BusinessException(ErrorCode.QR_NOT_CONFIGURED)) + pathPrefix + token

    companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val TOKEN_CONTEXT = "qr-token:v1:"
        const val MIN_SECRET_BYTES = 32

        /** Định dạng qrToken client gửi khi join (token mới 43 ký tự; QR dev dạng `dev-qr-T01`). */
        val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")

        fun parseSecret(raw: String): SecretKeySpec? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            val bytes = try {
                Base64.getDecoder().decode(value)
            } catch (ex: IllegalArgumentException) {
                throw IllegalStateException("QR_TOKEN_SECRET phải là chuỗi Base64 hợp lệ", ex)
            }
            check(bytes.size >= MIN_SECRET_BYTES) { "QR_TOKEN_SECRET phải dài tối thiểu $MIN_SECRET_BYTES byte (256 bit)" }
            return SecretKeySpec(bytes, HMAC_ALGORITHM)
        }

        fun parseBaseUrl(raw: String): String? {
            val value = raw.trim().trimEnd('/')
            if (value.isEmpty()) return null
            val uri = try {
                URI(value)
            } catch (ex: Exception) {
                throw IllegalStateException("QR_BASE_URL không phải URL hợp lệ", ex)
            }
            check(uri.scheme == "https" || uri.scheme == "http") { "QR_BASE_URL phải bắt đầu bằng http:// hoặc https://" }
            check(!uri.host.isNullOrBlank()) { "QR_BASE_URL thiếu tên miền" }
            check(uri.rawQuery == null && uri.rawFragment == null) { "QR_BASE_URL không được chứa query hoặc fragment" }
            return value
        }

        fun parsePathPrefix(raw: String): String {
            val value = raw.trim().ifEmpty { "/" }
            check(Regex("^/([A-Za-z0-9_-]+/)*$").matches(value)) { "app.qr.path-prefix phải dạng /t/ (bắt đầu và kết thúc bằng /)" }
            return value
        }
    }
}
