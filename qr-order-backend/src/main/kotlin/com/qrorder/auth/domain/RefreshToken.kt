package com.qrorder.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Generated
import org.hibernate.generator.EventType
import java.time.OffsetDateTime
import java.util.UUID

/** Lý do thu hồi refresh token (CHECK ck_refresh_token_revoke_reason). */
enum class RevokeReason {
    ROTATED,
    LOGOUT,
    REUSE_DETECTED,
    USER_LOCKED,
    USER_DISABLED,
    ROLE_CHANGED,
    PERMISSION_CHANGED,
    PASSWORD_CHANGED,
    ADMIN_REVOKED,
}

/**
 * Refresh token xoay vòng (FR-AUTH-02, DB-01). Chỉ lưu SHA-256 hex của token (NFR-04).
 *
 * Thu hồi và rotation KHÔNG đi qua dirty-checking của entity mà bằng câu UPDATE có điều kiện trong
 * [com.qrorder.auth.repository.RefreshTokenRepository] (DATABASE_DESIGN.md §5.3 (11)), vì bước 1 cần
 * biết số dòng bị ảnh hưởng để phát hiện token đã bị dùng lại. Các cột định danh bất biến
 * (trigger `trg_refresh_token_guard`) nên được đánh dấu updatable = false.
 */
@Entity
@Table(name = "refresh_token")
class RefreshToken(
    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: Long,

    @Column(name = "family_id", nullable = false, updatable = false)
    var familyId: UUID,

    @Column(name = "token_hash", nullable = false, updatable = false, columnDefinition = "bpchar(64)")
    var tokenHash: String,

    @Column(name = "expires_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    var expiresAt: OffsetDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    /** Lấy từ DEFAULT now() của database để cùng nguồn giờ với revoked_at (CHECK revoked_at >= issued_at). */
    @Generated(event = [EventType.INSERT])
    @Column(name = "issued_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var issuedAt: OffsetDateTime? = null
        protected set

    @Column(name = "revoked_at", insertable = false, updatable = false, columnDefinition = "timestamptz")
    var revokedAt: OffsetDateTime? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 32, insertable = false, updatable = false)
    var revokeReason: RevokeReason? = null
        protected set

    @Column(name = "replaced_by_id", insertable = false, updatable = false)
    var replacedById: Long? = null
        protected set

    val isRevoked: Boolean get() = revokedAt != null
}
