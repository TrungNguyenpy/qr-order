package com.qrorder.auth.dto

import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.User
import com.qrorder.auth.domain.UserPermission
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.domain.UserStatus
import com.qrorder.auth.password.StrongPassword
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

private const val REASON_MAX = 500

/** Thông tin user cho Admin (không bao giờ có password_hash). permissions = permission hiệu lực. */
data class UserResponse(
    val id: Long,
    val username: String,
    val fullName: String,
    val role: UserRole,
    val status: UserStatus,
    val permissions: List<Permission>,
    val lastLoginAt: OffsetDateTime?,
    val passwordChangedAt: OffsetDateTime?,
    val createdBy: Long?,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?,
    val version: Long,
) {
    companion object {
        fun of(user: User, permissions: List<Permission>) = UserResponse(
            id = requireNotNull(user.id),
            username = user.username,
            fullName = user.fullName,
            role = user.role,
            status = user.status,
            permissions = permissions,
            lastLoginAt = user.lastLoginAt,
            passwordChangedAt = user.passwordChangedAt,
            createdBy = user.createdBy,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
            version = user.version,
        )
    }
}

/**
 * FR-USER-01. Tạo user role ADMIN là cấp role ADMIN (BR-A08): bắt buộc [currentPassword] (xác thực lại)
 * và [reason]; kiểm tra ở UserService vì phụ thuộc giá trị role.
 */
data class CreateUserRequest(
    @field:NotBlank(message = "Tên đăng nhập là bắt buộc")
    @field:Pattern(
        regexp = "^[A-Za-z0-9._-]{3,50}$",
        message = "Tên đăng nhập 3-50 ký tự, chỉ gồm chữ không dấu, số, dấu chấm, gạch dưới, gạch ngang",
    )
    val username: String? = null,

    @field:NotBlank(message = "Họ tên là bắt buộc")
    @field:Size(max = 100, message = "Họ tên tối đa 100 ký tự")
    val fullName: String? = null,

    @field:NotNull(message = "Role là bắt buộc")
    val role: UserRole? = null,

    @field:NotBlank(message = "Mật khẩu là bắt buộc")
    @field:StrongPassword
    val password: String? = null,

    @field:Size(max = REASON_MAX, message = "Lý do tối đa 500 ký tự")
    val reason: String? = null,

    @field:Size(max = 256, message = "Mật khẩu quá dài")
    val currentPassword: String? = null,
) {
    override fun toString() = "CreateUserRequest(username=$username, fullName=$fullName, role=$role, password=***, reason=$reason, currentPassword=***)"
}

/** Sửa thông tin hồ sơ. Username không đổi được; role/trạng thái/mật khẩu có endpoint riêng. */
data class UpdateUserRequest(
    @field:NotBlank(message = "Họ tên là bắt buộc")
    @field:Size(max = 100, message = "Họ tên tối đa 100 ký tự")
    val fullName: String? = null,

    @field:NotNull(message = "Thiếu version (optimistic locking)")
    @field:PositiveOrZero(message = "version không hợp lệ")
    val version: Long? = null,
)

/** Khóa (LOCKED), ngừng dùng (DISABLED) hoặc mở lại (ACTIVE). Không xóa cứng user (BR-A03). */
data class ChangeUserStatusRequest(
    @field:NotNull(message = "Trạng thái là bắt buộc")
    val status: UserStatus? = null,

    @field:Size(max = REASON_MAX, message = "Lý do tối đa 500 ký tự")
    val reason: String? = null,
)

/**
 * Đổi role (FR-USER-04, BR-A08): chỉ ADMIN đang hoạt động, bắt buộc xác thực lại bằng mật khẩu hiện tại
 * của người thao tác và lý do. Áp dụng cho mọi lần đổi role để audit USER_ROLE_CHANGED luôn đầy đủ.
 */
data class ChangeUserRoleRequest(
    @field:NotNull(message = "Role mới là bắt buộc")
    val role: UserRole? = null,

    @field:NotBlank(message = "Lý do là bắt buộc")
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 500 ký tự")
    val reason: String? = null,

    @field:NotBlank(message = "Cần nhập mật khẩu hiện tại để xác thực lại")
    @field:Size(max = 256, message = "Mật khẩu quá dài")
    val currentPassword: String? = null,
) {
    override fun toString() = "ChangeUserRoleRequest(role=$role, reason=$reason, currentPassword=***)"
}

/** Admin đặt lại mật khẩu cho user khác (FR-USER-02). Mọi refresh token của user bị thu hồi. */
data class ResetPasswordRequest(
    @field:NotBlank(message = "Mật khẩu mới là bắt buộc")
    @field:StrongPassword
    val newPassword: String? = null,

    @field:Size(max = REASON_MAX, message = "Lý do tối đa 500 ký tự")
    val reason: String? = null,
) {
    override fun toString() = "ResetPasswordRequest(newPassword=***, reason=$reason)"
}

/**
 * Cấp permission (FR-USER-03). Tự cấp cho chính mình bắt buộc [currentPassword] (BR-A07);
 * cấp cho người khác: nếu gửi [currentPassword] thì cũng được kiểm tra và ghi reauthenticated = true.
 */
data class GrantPermissionRequest(
    @field:NotNull(message = "Permission là bắt buộc")
    val permission: Permission? = null,

    @field:NotBlank(message = "Lý do là bắt buộc")
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 500 ký tự")
    val reason: String? = null,

    @field:Size(max = 256, message = "Mật khẩu quá dài")
    val currentPassword: String? = null,
) {
    override fun toString() = "GrantPermissionRequest(permission=$permission, reason=$reason, currentPassword=***)"
}

/** Thu hồi permission; tự thu hồi của chính mình cũng bắt buộc xác thực lại (BR-A07 "thu hồi áp dụng tương tự"). */
data class RevokePermissionRequest(
    @field:NotBlank(message = "Lý do là bắt buộc")
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 500 ký tự")
    val reason: String? = null,

    @field:Size(max = 256, message = "Mật khẩu quá dài")
    val currentPassword: String? = null,
) {
    override fun toString() = "RevokePermissionRequest(reason=$reason, currentPassword=***)"
}

data class PermissionGrantResponse(
    val id: Long,
    val userId: Long,
    val permission: Permission,
    val active: Boolean,
    val grantedBy: Long,
    val grantedAt: OffsetDateTime?,
    val reason: String,
    val reauthenticated: Boolean,
    val revokedBy: Long?,
    val revokedAt: OffsetDateTime?,
    val revokeReason: String?,
) {
    companion object {
        fun of(p: UserPermission) = PermissionGrantResponse(
            id = requireNotNull(p.id),
            userId = p.userId,
            permission = p.permission,
            active = p.isActive,
            grantedBy = p.grantedBy,
            grantedAt = p.grantedAt,
            reason = p.reason,
            reauthenticated = p.reauthenticated,
            revokedBy = p.revokedBy,
            revokedAt = p.revokedAt,
            revokeReason = p.revokeReason,
        )
    }
}
