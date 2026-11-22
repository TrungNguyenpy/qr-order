package com.qrorder.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.Generated
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.generator.EventType
import java.time.OffsetDateTime

/** DEC-13 A: role là enum trên user, không có bảng roles. ADMIN không kế thừa MANAGER (BR-A06). */
enum class UserRole { ADMIN, MANAGER, STAFF, KITCHEN }

/** BR-A04: khóa/ngừng dùng có hiệu lực ngay (filter kiểm tra mỗi request, trigger V3 thu hồi refresh token). */
enum class UserStatus { ACTIVE, LOCKED, DISABLED }

/**
 * Tài khoản nhân viên. Không có tài khoản mặc định; ADMIN đầu tiên do khởi tạo triển khai (BR-A09).
 * Trigger `trg_users_keep_one_active_admin` bảo đảm luôn còn >= 1 ADMIN ACTIVE.
 * passwordHash không bao giờ được trả ra API hay ghi log (NFR-04).
 */
@Entity
@Table(name = "users")
class User(
    @Column(name = "username", nullable = false, length = 50)
    var username: String,

    @Column(name = "password_hash", nullable = false, length = 255)
    var passwordHash: String,

    @Column(name = "full_name", nullable = false, length = 100)
    var fullName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    var role: UserRole,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: UserStatus = UserStatus.ACTIVE

    @Column(name = "last_login_at", columnDefinition = "timestamptz")
    var lastLoginAt: OffsetDateTime? = null

    @Column(name = "password_changed_at", columnDefinition = "timestamptz")
    var passwordChangedAt: OffsetDateTime? = null

    @Column(name = "created_by", updatable = false)
    var createdBy: Long? = null

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var createdAt: OffsetDateTime? = null
        protected set

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    var updatedAt: OffsetDateTime? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    val isActive: Boolean get() = status == UserStatus.ACTIVE
}
