package com.qrorder.auth.security

import com.qrorder.auth.domain.UserRole
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Danh tính của người gọi API/STOMP sau khi xác thực JWT.
 * - [StaffPrincipal]: nhân viên đăng nhập (ADMIN, MANAGER, STAFF, KITCHEN).
 * - [GuestPrincipal]: khách theo phiên bàn (Guest JWT, FR-AUTH-04) - không phải user.
 */
sealed interface AuthPrincipal {
    val name: String
    fun authorities(): List<SimpleGrantedAuthority>
}

data class StaffPrincipal(
    val userId: Long,
    val username: String,
    val role: UserRole,
) : AuthPrincipal {
    override val name: String get() = username
    override fun authorities() = listOf(SimpleGrantedAuthority("ROLE_${role.name}"))
}

data class GuestPrincipal(
    val sessionId: Long,
    val deviceId: String,
) : AuthPrincipal {
    override val name: String get() = "guest:$sessionId:$deviceId"
    override fun authorities() = listOf(SimpleGrantedAuthority(ROLE_GUEST))

    companion object {
        const val ROLE_GUEST = "ROLE_GUEST"
    }
}

fun AuthPrincipal.toAuthentication(): Authentication =
    UsernamePasswordAuthenticationToken.authenticated(this, null, authorities())

/** Truy cập principal của request hiện tại. */
object CurrentPrincipal {
    fun get(): AuthPrincipal? = SecurityContextHolder.getContext().authentication?.principal as? AuthPrincipal

    fun staffOrNull(): StaffPrincipal? = get() as? StaffPrincipal

    /**
     * Principal nhân viên của request hiện tại. Endpoint gọi hàm này đã được @PreAuthorize chặn Guest/ẩn danh;
     * nếu vẫn không có (cấu hình sai) thì từ chối 403 thay vì chạy tiếp.
     */
    fun requireStaff(): StaffPrincipal =
        staffOrNull() ?: throw BusinessException(ErrorCode.ACCESS_DENIED)
}
