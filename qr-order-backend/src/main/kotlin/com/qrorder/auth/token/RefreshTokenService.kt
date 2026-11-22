package com.qrorder.auth.token

import com.qrorder.auth.domain.RefreshToken
import com.qrorder.auth.domain.RevokeReason
import com.qrorder.auth.repository.RefreshTokenRepository
import com.qrorder.auth.security.JwtProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Refresh token vừa phát hành; [rawToken] chỉ trả cho client một lần, DB chỉ giữ hash. */
data class IssuedRefreshToken(
    val rawToken: String,
    val familyId: UUID,
    val expiresAt: OffsetDateTime,
)

sealed interface RotationResult {
    /** Rotation thành công: token cũ ROTATED, trỏ tới token mới. */
    data class Rotated(val userId: Long, val token: IssuedRefreshToken) : RotationResult

    /** Token đã bị dùng/thu hồi trước đó: toàn bộ family đã bị thu hồi với REUSE_DETECTED. */
    data class ReuseDetected(val userId: Long, val familyId: UUID) : RotationResult

    /** Token còn hiệu lực nhưng đã hết hạn. */
    data object Expired : RotationResult

    /** Không tìm thấy token. */
    data object Invalid : RotationResult
}

/**
 * Lưu trữ và xoay vòng refresh token theo đúng quy ước DATABASE_DESIGN.md §5.3 (11) (FR-AUTH-02).
 * Endpoint /api/v1/auth/refresh dùng service này ở Giai đoạn 1.
 *
 * Rotation chạy 3 bước trong CÙNG MỘT transaction:
 *  1. UPDATE token cũ SET revoked_at = now(), revoke_reason = 'ROTATED' WHERE revoked_at IS NULL.
 *     0 dòng => token đã bị dùng hoặc đã thu hồi => coi là REUSE: thu hồi toàn bộ family_id.
 *  2. INSERT token mới cùng family_id.
 *  3. UPDATE token cũ SET replaced_by_id = id token mới.
 * Trigger DEFERRABLE `trg_refresh_token_rotation_complete` kiểm tra lúc COMMIT: token ROTATED mà
 * thiếu token kế tiếp => REFRESH_TOKEN_ROTATION_INCOMPLETE, cả transaction rollback.
 *
 * Hai request refresh song song cùng một token: câu UPDATE bước 1 khóa dòng nên chỉ một bên
 * nhận 1 dòng; bên còn lại nhận 0 dòng và thu hồi cả family (kể cả token vừa cấp cho bên kia).
 * Đây là hành vi chủ đích của chính sách reuse detection đã được duyệt.
 *
 * Kết quả ReuseDetected được trả về (không ném exception) để việc thu hồi family vẫn được COMMIT.
 */
@Service
class RefreshTokenService(
    private val repository: RefreshTokenRepository,
    private val hasher: TokenHasher,
    private val jwtProperties: JwtProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Đăng nhập mới: mở một family mới. */
    @Transactional
    fun issueForLogin(userId: Long): IssuedRefreshToken = issue(userId, UUID.randomUUID()).second

    @Transactional
    fun rotate(rawToken: String): RotationResult {
        val current = repository.findByTokenHash(hasher.sha256Hex(rawToken)) ?: return RotationResult.Invalid
        val currentId = requireNotNull(current.id)

        if (!current.isRevoked && !current.expiresAt.isAfter(now())) {
            return RotationResult.Expired
        }

        // Bước 1: đánh dấu ROTATED có điều kiện.
        if (repository.markRotated(currentId) == 0) {
            val revoked = repository.revokeActiveInFamily(current.familyId, RevokeReason.REUSE_DETECTED.name)
            log.warn(
                "Phát hiện dùng lại refresh token: userId={} family={} đã thu hồi {} token",
                current.userId, current.familyId, revoked,
            )
            return RotationResult.ReuseDetected(current.userId, current.familyId)
        }

        // Bước 2: token mới cùng family.
        val (nextId, next) = issue(current.userId, current.familyId)

        // Bước 3: nối chuỗi rotation.
        repository.linkReplacement(currentId, nextId)
        return RotationResult.Rotated(current.userId, next)
    }

    /** Đăng xuất: thu hồi family của token (FR-AUTH-02). Trả về số token bị thu hồi. */
    @Transactional
    fun logout(rawToken: String): Int {
        val current = repository.findByTokenHash(hasher.sha256Hex(rawToken)) ?: return 0
        return repository.revokeActiveInFamily(current.familyId, RevokeReason.LOGOUT.name)
    }

    /**
     * Thu hồi mọi token của user khi đổi permission/mật khẩu hoặc Admin thu hồi (BR-A04).
     * Khóa tài khoản và đổi role đã được trigger `trg_users_revoke_refresh_tokens` thu hồi tự động.
     */
    @Transactional
    fun revokeAllForUser(userId: Long, reason: RevokeReason): Int {
        require(reason in USER_LEVEL_REASONS) { "Lý do $reason không dùng để thu hồi theo user" }
        return repository.revokeAllActiveForUser(userId, reason.name)
    }

    /** Lưu token mới (flush ngay để có id cho bước 3); trả về (id, token gốc cho client). */
    private fun issue(userId: Long, familyId: UUID): Pair<Long, IssuedRefreshToken> {
        val raw = hasher.newRawToken()
        val expiresAt = now().plus(jwtProperties.refreshTokenTtl)
        val saved = repository.saveAndFlush(RefreshToken(userId, familyId, hasher.sha256Hex(raw), expiresAt))
        return requireNotNull(saved.id) to IssuedRefreshToken(raw, familyId, expiresAt)
    }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    companion object {
        private val USER_LEVEL_REASONS = setOf(
            RevokeReason.PERMISSION_CHANGED,
            RevokeReason.PASSWORD_CHANGED,
            RevokeReason.ADMIN_REVOKED,
        )
    }
}
