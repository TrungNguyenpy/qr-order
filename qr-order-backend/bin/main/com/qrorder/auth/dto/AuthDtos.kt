package com.qrorder.auth.dto

import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.User
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.domain.UserStatus
import com.qrorder.auth.password.StrongPassword
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.OffsetDateTime

// Quy tắc 7 / NFR-04: mọi DTO chứa mật khẩu hoặc token ghi đè toString() để không lộ giá trị khi bị log.

data class LoginRequest(
    @field:NotBlank(message = "Tên đăng nhập là bắt buộc")
    @field:Size(max = 50, message = "Tên đăng nhập tối đa 50 ký tự")
    val username: String? = null,

    @field:NotBlank(message = "Mật khẩu là bắt buộc")
    @field:Size(max = 256, message = "Mật khẩu quá dài")
    val password: String? = null,
) {
    override fun toString() = "LoginRequest(username=$username, password=***)"
}

data class RefreshTokenRequest(
    @field:NotBlank(message = "Thiếu refresh token")
    @field:Size(max = 256, message = "Refresh token không hợp lệ")
    val refreshToken: String? = null,
) {
    override fun toString() = "RefreshTokenRequest(refreshToken=***)"
}

data class LogoutRequest(
    @field:NotBlank(message = "Thiếu refresh token")
    @field:Size(max = 256, message = "Refresh token không hợp lệ")
    val refreshToken: String? = null,
) {
    override fun toString() = "LogoutRequest(refreshToken=***)"
}

/** Tự đổi mật khẩu (FR-USER-02). Thành công thì mọi refresh token bị thu hồi (PASSWORD_CHANGED): đăng nhập lại. */
data class ChangePasswordRequest(
    @field:NotBlank(message = "Mật khẩu hiện tại là bắt buộc")
    @field:Size(max = 256, message = "Mật khẩu quá dài")
    val currentPassword: String? = null,

    @field:NotBlank(message = "Mật khẩu mới là bắt buộc")
    @field:StrongPassword
    val newPassword: String? = null,
) {
    override fun toString() = "ChangePasswordRequest(currentPassword=***, newPassword=***)"
}

/**
 * Người dùng hiện tại (GET /api/v1/auth/me) kèm permission hiệu lực: MANAGER có đủ 8 permission mặc định
 * (BR-A06), role khác chỉ gồm permission được cấp còn hiệu lực.
 */
data class CurrentUserResponse(
    val id: Long,
    val username: String,
    val fullName: String,
    val role: UserRole,
    val status: UserStatus,
    val permissions: List<Permission>,
    val lastLoginAt: OffsetDateTime?,
    val passwordChangedAt: OffsetDateTime?,
) {
    companion object {
        fun of(user: User, permissions: List<Permission>) = CurrentUserResponse(
            id = requireNotNull(user.id),
            username = user.username,
            fullName = user.fullName,
            role = user.role,
            status = user.status,
            permissions = permissions,
            lastLoginAt = user.lastLoginAt,
            passwordChangedAt = user.passwordChangedAt,
        )
    }
}

/**
 * Kết quả đăng nhập / refresh (FR-AUTH-01, FR-AUTH-02). refreshToken chỉ trả một lần; database chỉ lưu hash.
 * Client phải tuần tự hóa việc refresh (BE-06): hai request refresh song song cùng một token bị coi là dùng lại
 * và cả phiên đăng nhập bị thu hồi.
 */
data class TokenResponse(
    val tokenType: String = "Bearer",
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: OffsetDateTime,
    val user: CurrentUserResponse,
) {
    override fun toString() = "TokenResponse(tokenType=$tokenType, accessToken=***, refreshToken=***, user=${user.username})"
}
