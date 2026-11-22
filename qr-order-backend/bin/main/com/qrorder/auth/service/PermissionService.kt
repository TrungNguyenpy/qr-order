package com.qrorder.auth.service

import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.RevokeReason
import com.qrorder.auth.domain.User
import com.qrorder.auth.domain.UserPermission
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.dto.GrantPermissionRequest
import com.qrorder.auth.dto.PermissionGrantResponse
import com.qrorder.auth.dto.RevokePermissionRequest
import com.qrorder.auth.password.PasswordService
import com.qrorder.auth.repository.UserPermissionRepository
import com.qrorder.auth.repository.UserRepository
import com.qrorder.auth.token.RefreshTokenService
import com.qrorder.common.api.ErrorDetail
import com.qrorder.common.audit.AuditActions
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEntityTypes
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Permission nghiệp vụ (FR-USER-03, SRS §10, BR-A06..A08).
 *
 * - MANAGER mặc định có đủ 8 permission (suy từ role, không lưu dòng). Role khác chỉ có permission được cấp.
 * - Cấp/thu hồi do ADMIN (controller chặn role khác). Tự cấp/thu hồi cho chính mình bắt buộc xác thực lại
 *   bằng mật khẩu hiện tại (BR-A07); lý do luôn bắt buộc; ghi audit PERMISSION_GRANTED / PERMISSION_REVOKED.
 * - Sau khi đổi quyền: thu hồi mọi refresh token của user bị đổi quyền (PERMISSION_CHANGED, BR-A04).
 *   Bản thân việc kiểm tra quyền đã đọc database mỗi request nên có hiệu lực ngay cả với access token cũ.
 * - Không có permission nào tương ứng role ADMIN: cơ chế này không thể cấp/đổi role (BR-A08).
 */
@Service
class PermissionService(
    private val permissionRepository: UserPermissionRepository,
    private val userRepository: UserRepository,
    private val passwordService: PasswordService,
    private val refreshTokenService: RefreshTokenService,
    private val auditService: AuditService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Permission hiệu lực của một user, sắp theo thứ tự khai báo enum. */
    fun effectivePermissions(userId: Long, role: UserRole): List<Permission> =
        if (role == UserRole.MANAGER) {
            Permission.entries.toList()
        } else {
            permissionRepository.findActivePermissions(userId).distinct().sortedBy { it.ordinal }
        }

    /** Permission hiệu lực của nhiều user bằng một truy vấn (dùng cho danh sách user). */
    fun effectivePermissions(users: Collection<User>): Map<Long, List<Permission>> {
        val ids = users.mapNotNull { it.id }
        if (ids.isEmpty()) return emptyMap()
        val granted = permissionRepository.findByUserIdInAndRevokedAtIsNull(ids)
            .groupBy({ it.userId }, { it.permission })
        return users.filter { it.id != null }.associate { user ->
            val id = requireNotNull(user.id)
            id to if (user.role == UserRole.MANAGER) {
                Permission.entries.toList()
            } else {
                granted[id].orEmpty().distinct().sortedBy { it.ordinal }
            }
        }
    }

    @Transactional(readOnly = true)
    fun listGrants(userId: Long, includeRevoked: Boolean): List<PermissionGrantResponse> {
        loadUser(userId)
        val grants = if (includeRevoked) {
            permissionRepository.findByUserIdOrderByGrantedAtDescIdDesc(userId)
        } else {
            permissionRepository.findByUserIdAndRevokedAtIsNullOrderByPermissionAsc(userId)
        }
        return grants.map(PermissionGrantResponse::of)
    }

    @Transactional
    fun grant(actorUserId: Long, targetUserId: Long, request: GrantPermissionRequest): PermissionGrantResponse {
        val permission = requireNotNull(request.permission)
        val reason = requireNotNull(request.reason).trim()
        val target = loadUser(targetUserId)
        if (target.role == UserRole.MANAGER) {
            throw BusinessException(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                "MANAGER mặc định đã có đủ 8 permission nghiệp vụ, không cần cấp thêm (BR-A06)",
            )
        }
        val reauthenticated = reauthenticate(actorUserId, targetUserId, request.currentPassword)

        if (permissionRepository.existsByUserIdAndPermissionAndRevokedAtIsNull(targetUserId, permission)) {
            throw BusinessException(ErrorCode.PERMISSION_ALREADY_GRANTED)
        }
        // Unique index ux_user_permission_active là hàng rào cuối khi hai Admin cấp cùng lúc (-> 409).
        val saved = permissionRepository.saveAndFlush(
            UserPermission(
                userId = targetUserId,
                permission = permission,
                grantedBy = actorUserId,
                reason = reason,
                reauthenticated = reauthenticated,
            ),
        )
        val revokedTokens = refreshTokenService.revokeAllForUser(targetUserId, RevokeReason.PERMISSION_CHANGED)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = AuditActions.PERMISSION_GRANTED,
                entityType = AuditEntityTypes.USER_PERMISSION,
                entityId = saved.id,
                newValue = mapOf(
                    "userId" to targetUserId,
                    "permission" to permission,
                    "selfGrant" to (actorUserId == targetUserId),
                ),
                reason = reason,
                reauthenticated = reauthenticated,
            ),
        )
        log.info(
            "Cấp permission {} cho userId={} bởi userId={}; thu hồi {} refresh token",
            permission, targetUserId, actorUserId, revokedTokens,
        )
        return PermissionGrantResponse.of(saved)
    }

    @Transactional
    fun revoke(
        actorUserId: Long,
        targetUserId: Long,
        permission: Permission,
        request: RevokePermissionRequest,
    ): PermissionGrantResponse {
        val reason = requireNotNull(request.reason).trim()
        loadUser(targetUserId)
        val reauthenticated = reauthenticate(actorUserId, targetUserId, request.currentPassword)

        val grant = permissionRepository.findByUserIdAndPermissionAndRevokedAtIsNull(targetUserId, permission)
            ?: throw BusinessException(ErrorCode.PERMISSION_NOT_GRANTED)
        val grantId = requireNotNull(grant.id)
        // UPDATE có điều kiện revoked_at IS NULL: 0 dòng = Admin khác vừa thu hồi trước.
        if (permissionRepository.revoke(grantId, actorUserId, reason) == 0) {
            throw BusinessException(ErrorCode.PERMISSION_ALREADY_REVOKED)
        }
        val revokedTokens = refreshTokenService.revokeAllForUser(targetUserId, RevokeReason.PERMISSION_CHANGED)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = AuditActions.PERMISSION_REVOKED,
                entityType = AuditEntityTypes.USER_PERMISSION,
                entityId = grantId,
                oldValue = mapOf("userId" to targetUserId, "permission" to permission, "active" to true),
                newValue = mapOf("userId" to targetUserId, "permission" to permission, "active" to false),
                reason = reason,
                reauthenticated = reauthenticated,
            ),
        )
        log.info(
            "Thu hồi permission {} của userId={} bởi userId={}; thu hồi {} refresh token",
            permission, targetUserId, actorUserId, revokedTokens,
        )
        val revoked = permissionRepository.findById(grantId).orElseThrow { ResourceNotFoundException("bản cấp quyền", grantId) }
        return PermissionGrantResponse.of(revoked)
    }

    /**
     * BR-A07: thao tác trên chính mình bắt buộc xác thực lại. Thao tác trên người khác: nếu client gửi
     * mật khẩu thì vẫn kiểm tra và ghi nhận reauthenticated = true.
     */
    private fun reauthenticate(actorUserId: Long, targetUserId: Long, currentPassword: String?): Boolean {
        if (currentPassword.isNullOrEmpty()) {
            if (actorUserId == targetUserId) {
                throw BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    details = listOf(
                        ErrorDetail(
                            "currentPassword",
                            "Tự cấp hoặc thu hồi permission của chính mình phải nhập mật khẩu hiện tại (BR-A07)",
                        ),
                    ),
                )
            }
            return false
        }
        passwordService.verifyCurrentPassword(actorUserId, currentPassword)
        return true
    }

    private fun loadUser(userId: Long): User =
        userRepository.findById(userId).orElseThrow { ResourceNotFoundException("người dùng", userId) }
}
