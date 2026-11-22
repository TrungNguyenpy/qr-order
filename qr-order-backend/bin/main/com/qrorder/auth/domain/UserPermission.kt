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

/**
 * 8 permission nghiệp vụ (SRS §10, BR-A06). MANAGER mặc định có cả 8 (suy từ role, không lưu dòng);
 * STAFF/KITCHEN/ADMIN chỉ có khi được cấp qua user_permission.
 */
enum class Permission {
    PAYMENT_PROCESS,
    BILL_DISCOUNT,
    BILL_VOID,
    REFUND_RECORD,
    ITEM_CANCEL_APPROVE,
    SESSION_FORCE_CLOSE,
    SESSION_REOPEN,
    REPORT_VIEW,
}

/**
 * Bản cấp permission. Chỉ được cập nhật một lần để thu hồi (trigger `trg_user_permission_guard`);
 * thu hồi giữ lịch sử, không xóa. Tự cấp cho chính mình bắt buộc reauthenticated = true (BR-A07).
 * Không dùng để cấp/đổi role ADMIN (BR-A08).
 */
@Entity
@Table(name = "user_permission")
class UserPermission(
    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 32, updatable = false)
    var permission: Permission,

    @Column(name = "granted_by", nullable = false, updatable = false)
    var grantedBy: Long,

    @Column(name = "reason", nullable = false, updatable = false, columnDefinition = "text")
    var reason: String,

    @Column(name = "reauthenticated", nullable = false, updatable = false)
    var reauthenticated: Boolean = false,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Generated(event = [EventType.INSERT])
    @Column(name = "granted_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var grantedAt: OffsetDateTime? = null
        protected set

    @Column(name = "revoked_by")
    var revokedBy: Long? = null

    @Column(name = "revoked_at", columnDefinition = "timestamptz")
    var revokedAt: OffsetDateTime? = null

    @Column(name = "revoke_reason", columnDefinition = "text")
    var revokeReason: String? = null

    val isActive: Boolean get() = revokedAt == null
}
