package com.qrorder.table.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Cấu hình QR bàn (app.qr.*, Giai đoạn 3 - FR-QR-01, BR-S05).
 *
 * - [baseUrl]: địa chỉ Customer Web (không có "/" cuối). URL trên QR = baseUrl + [pathPrefix] + qrToken
 *   (SRS §3.1: `https://{domain}/t/{qrToken}`).
 * - [tokenSecret]: khóa HMAC (Base64, >= 32 byte) để dựng lại qrToken khi in lại; DB chỉ lưu SHA-256 của token.
 * Thiếu một trong hai: API sinh/xoay/in QR trả 503 QR_NOT_CONFIGURED (ứng dụng vẫn khởi động, join vẫn chạy).
 * Giá trị có nhưng sai định dạng: ứng dụng dừng khi khởi động (lỗi cấu hình rõ ràng) - xem QrTokenCodec.
 */
@ConfigurationProperties(prefix = "app.qr")
data class QrProperties(
    val baseUrl: String = "",
    val pathPrefix: String = "/t/",
    val tokenSecret: String = "",
    val image: Image = Image(),
    val pdf: Pdf = Pdf(),
) {
    /** Ảnh PNG: kích thước (px) client được chọn trong [minSize, maxSize]; [margin] là vùng trắng (số module QR). */
    data class Image(
        val defaultSize: Int = 512,
        val minSize: Int = 128,
        val maxSize: Int = 2048,
        val margin: Int = 2,
    )

    /**
     * PDF in QR: [fontPath] là font TrueType Unicode nhúng vào PDF để in tiếng Việt (đường dẫn file trên máy hoặc
     * resource trong classpath; mặc định Liberation Sans có sẵn trong OpenPDF 3.x). [qrSizePt] là cạnh mã QR trên
     * trang (point, 72 pt = 1 inch). [maxTables] giới hạn số bàn mỗi lần in hàng loạt.
     */
    data class Pdf(
        val fontPath: String = "font-fallback/LiberationSans-Regular.ttf",
        val qrSizePt: Float = 300f,
        val maxTables: Int = 200,
    )
}

/**
 * Cấu hình phiên bàn (app.session.*): chu kỳ quét phiên idle (BR-S07, UC36) và số phiên tối đa xử lý mỗi lượt.
 * Idle timeout, TTL Guest JWT, rate limit join, PIN đọc từ restaurant_settings (không cấu hình ở đây).
 */
@ConfigurationProperties(prefix = "app.session")
data class SessionProperties(
    val expiryCheckIntervalMs: Long = 60_000,
    val expiryBatchSize: Int = 200,
)
