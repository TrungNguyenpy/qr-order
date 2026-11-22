package com.qrorder.auth.security

import com.qrorder.auth.domain.UserRole
import com.qrorder.common.error.ErrorCode
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

/** Lỗi xác thực token; filter/interceptor chuyển thành 401 với [errorCode]. */
class AuthTokenException(val errorCode: ErrorCode, message: String = errorCode.defaultMessage) : RuntimeException(message)

data class IssuedAccessToken(val token: String, val expiresAt: Instant)

/**
 * Phát hành và kiểm tra JWT (HMAC-SHA). Hai loại token phân biệt bằng claim `typ`:
 * - STAFF: sub = userId, claim `role`, `username`; TTL app.security.jwt.access-token-ttl.
 * - GUEST: sub = "guest:{sessionId}", claim `sid`, `did`; TTL lấy từ restaurant_settings.guest_jwt_ttl_minutes
 *   (truyền vào khi phát hành, GĐ3 - FR-AUTH-04).
 *
 * Quyền (permission) KHÔNG nằm trong token mà kiểm tra ở database mỗi lần cần (BR-A04: đổi
 * permission có hiệu lực ngay).
 */
@Service
class JwtService(
    private val properties: JwtProperties,
    private val clock: Clock,
) {
    private val key: SecretKey = run {
        val bytes = try {
            Decoders.BASE64.decode(properties.secret.trim())
        } catch (ex: RuntimeException) {
            throw IllegalStateException("JWT_SECRET phải là chuỗi Base64 hợp lệ", ex)
        }
        require(bytes.size >= MIN_SECRET_BYTES) { "JWT_SECRET phải dài tối thiểu $MIN_SECRET_BYTES byte (256 bit)" }
        Keys.hmacShaKeyFor(bytes)
    }

    fun issueStaffAccessToken(userId: Long, username: String, role: UserRole): IssuedAccessToken {
        val now = clock.instant()
        val expiresAt = now.plus(properties.accessTokenTtl)
        val token = Jwts.builder()
            .id(UUID.randomUUID().toString())
            .issuer(properties.issuer)
            .subject(userId.toString())
            .claim(CLAIM_TYPE, TYPE_STAFF)
            .claim(CLAIM_ROLE, role.name)
            .claim(CLAIM_USERNAME, username)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(key)
            .compact()
        return IssuedAccessToken(token, expiresAt)
    }

    fun issueGuestToken(sessionId: Long, deviceId: String, ttl: Duration): IssuedAccessToken {
        val now = clock.instant()
        val expiresAt = now.plus(ttl)
        val token = Jwts.builder()
            .id(UUID.randomUUID().toString())
            .issuer(properties.issuer)
            .subject("guest:$sessionId")
            .claim(CLAIM_TYPE, TYPE_GUEST)
            .claim(CLAIM_SESSION_ID, sessionId)
            .claim(CLAIM_DEVICE_ID, deviceId)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(key)
            .compact()
        return IssuedAccessToken(token, expiresAt)
    }

    /** Kiểm tra chữ ký, issuer, hạn dùng; trả về principal. Ném [AuthTokenException] nếu không hợp lệ. */
    fun parse(token: String): AuthPrincipal {
        val claims: Claims = try {
            Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer)
                .clock { Date.from(clock.instant()) }
                .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (ex: ExpiredJwtException) {
            throw AuthTokenException(ErrorCode.TOKEN_EXPIRED)
        } catch (ex: JwtException) {
            throw AuthTokenException(ErrorCode.TOKEN_INVALID)
        } catch (ex: IllegalArgumentException) {
            throw AuthTokenException(ErrorCode.TOKEN_INVALID)
        }
        return try {
            when (claims[CLAIM_TYPE]) {
                TYPE_STAFF -> StaffPrincipal(
                    userId = claims.subject.toLong(),
                    username = claims[CLAIM_USERNAME] as String,
                    role = UserRole.valueOf(claims[CLAIM_ROLE] as String),
                )
                TYPE_GUEST -> GuestPrincipal(
                    sessionId = (claims[CLAIM_SESSION_ID] as Number).toLong(),
                    deviceId = claims[CLAIM_DEVICE_ID] as String,
                )
                else -> throw AuthTokenException(ErrorCode.TOKEN_INVALID)
            }
        } catch (ex: AuthTokenException) {
            throw ex
        } catch (ex: RuntimeException) {
            // Thiếu claim, sai kiểu, role không tồn tại...
            throw AuthTokenException(ErrorCode.TOKEN_INVALID)
        }
    }

    companion object {
        const val MIN_SECRET_BYTES = 32
        const val CLOCK_SKEW_SECONDS = 30L
        const val CLAIM_TYPE = "typ"
        const val CLAIM_ROLE = "role"
        const val CLAIM_USERNAME = "username"
        const val CLAIM_SESSION_ID = "sid"
        const val CLAIM_DEVICE_ID = "did"
        const val TYPE_STAFF = "STAFF"
        const val TYPE_GUEST = "GUEST"
    }
}
