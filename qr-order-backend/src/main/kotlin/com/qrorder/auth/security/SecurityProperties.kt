package com.qrorder.auth.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Cấu hình JWT (app.security.jwt.*). secret bắt buộc lấy từ biến môi trường JWT_SECRET (Base64,
 * >= 32 byte), không hardcode (quy tắc 6, NFR-04). Kiểm tra độ dài khóa ở [JwtService] khi khởi động.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
data class JwtProperties(
    val issuer: String,
    val secret: String,
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    val refreshTokenTtl: Duration = Duration.ofDays(7),
)

/** CORS whitelist (NFR-04). Danh sách rỗng = không cho phép origin nào gọi từ trình duyệt. */
@ConfigurationProperties(prefix = "app.security.cors")
data class CorsProperties(
    val allowedOrigins: List<String> = emptyList(),
)
