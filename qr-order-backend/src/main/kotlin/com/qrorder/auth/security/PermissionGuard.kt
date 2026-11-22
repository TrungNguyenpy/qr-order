package com.qrorder.auth.security

import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.repository.UserPermissionRepository
import org.springframework.stereotype.Component

/**
 * Kiểm tra permission nghiệp vụ động (SRS §10, BR-A06), dùng trong method security:
 *
 * ```
 * @PreAuthorize("@perm.has('PAYMENT_PROCESS')")
 * ```
 *
 * Quy tắc:
 * - MANAGER: mặc định có cả 8 permission (suy từ role, không lưu dòng user_permission).
 * - STAFF, KITCHEN, ADMIN: chỉ khi có bản cấp còn hiệu lực trong user_permission.
 *   ADMIN KHÔNG kế thừa quyền MANAGER (O3, BR-A06).
 * - Guest: không bao giờ có permission.
 *
 * Đọc database mỗi lần kiểm tra để thu hồi/cấp quyền có hiệu lực ngay (BR-A04).
 */
@Component("perm")
class PermissionGuard(
    private val userPermissionRepository: UserPermissionRepository,
) {
    /** Dùng trong SpEL với tên permission dạng chuỗi. */
    fun has(permission: String): Boolean = hasPermission(Permission.valueOf(permission))

    fun hasPermission(permission: Permission): Boolean {
        val principal = CurrentPrincipal.staffOrNull() ?: return false
        return hasPermission(principal, permission)
    }

    fun hasPermission(principal: StaffPrincipal, permission: Permission): Boolean =
        principal.role == UserRole.MANAGER ||
            userPermissionRepository.existsByUserIdAndPermissionAndRevokedAtIsNull(principal.userId, permission)
}
