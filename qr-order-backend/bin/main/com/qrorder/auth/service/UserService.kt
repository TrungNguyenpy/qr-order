package com.qrorder.auth.service

import com.qrorder.auth.domain.RevokeReason
import com.qrorder.auth.domain.User
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.domain.UserStatus
import com.qrorder.auth.dto.ChangeUserRoleRequest
import com.qrorder.auth.dto.ChangeUserStatusRequest
import com.qrorder.auth.dto.CreateUserRequest
import com.qrorder.auth.dto.ResetPasswordRequest
import com.qrorder.auth.dto.UpdateUserRequest
import com.qrorder.auth.dto.UserResponse
import com.qrorder.auth.password.PasswordService
import com.qrorder.auth.repository.UserRepository
import com.qrorder.auth.token.RefreshTokenService
import com.qrorder.common.api.ErrorDetail
import com.qrorder.common.api.PageResponse
import com.qrorder.common.audit.AuditActions
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEntityTypes
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime

/** Bộ lọc danh sách user cho Admin. */
object UserSpecifications {
    private const val LIKE_ESCAPE = '\\'

    fun matching(role: UserRole?, status: UserStatus?, keyword: String?): Specification<User> =
        Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            if (role != null) predicates += cb.equal(root.get<UserRole>("role"), role)
            if (status != null) predicates += cb.equal(root.get<UserStatus>("status"), status)
            val term = keyword?.trim()?.takeIf { it.isNotEmpty() }
            if (term != null) {
                val pattern = "%" + escapeLike(term.lowercase()) + "%"
                predicates += cb.or(
                    cb.like(cb.lower(root.get("username")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("fullName")), pattern, LIKE_ESCAPE),
                )
            }
            cb.and(*predicates.toTypedArray())
        }

    /** Thoát ký tự đại diện của LIKE để từ khóa được hiểu theo nghĩa đen. */
    fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

/**
 * Quản lý tài khoản nhân viên (FR-USER-01, FR-USER-02, FR-USER-04). Mọi thao tác do ADMIN (controller chặn).
 *
 * - Không xóa cứng user (BR-A03, trigger chặn DELETE): ngừng dùng bằng trạng thái DISABLED.
 * - Khóa/ngừng dùng/đổi role có hiệu lực ngay (BR-A04): filter JWT đối chiếu users mỗi request và trigger
 *   `trg_users_revoke_refresh_tokens` thu hồi refresh token trong cùng transaction.
 * - Luôn còn >= 1 ADMIN ACTIVE (BR-A09): kiểm tra sớm ở đây để trả lỗi rõ ràng; trigger
 *   `trg_users_keep_one_active_admin` (advisory lock) là hàng rào cuối khi hai ADMIN thao tác đồng thời.
 * - Cấp role ADMIN (đổi role hoặc tạo user ADMIN) bắt buộc xác thực lại + lý do + audit (BR-A08).
 */
@Service
class UserService(
    private val userRepository: UserRepository,
    private val permissionService: PermissionService,
    private val passwordService: PasswordService,
    private val refreshTokenService: RefreshTokenService,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun search(role: UserRole?, status: UserStatus?, keyword: String?, pageable: Pageable): PageResponse<UserResponse> {
        val page = userRepository.findAll(UserSpecifications.matching(role, status, keyword), pageable)
        val permissions = permissionService.effectivePermissions(page.content)
        return PageResponse.of(page) { UserResponse.of(it, permissions[it.id].orEmpty()) }
    }

    @Transactional(readOnly = true)
    fun get(userId: Long): UserResponse = toResponse(load(userId))

    @Transactional
    fun create(actorUserId: Long, request: CreateUserRequest): UserResponse {
        val username = requireNotNull(request.username).trim()
        val fullName = requireNotNull(request.fullName).trim()
        val role = requireNotNull(request.role)
        val reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }

        var reauthenticated = false
        if (role == UserRole.ADMIN) {
            // BR-A08: tạo tài khoản ADMIN = cấp role ADMIN.
            val missing = buildList {
                if (reason == null) add(ErrorDetail("reason", "Tạo tài khoản ADMIN phải có lý do (BR-A08)"))
                if (request.currentPassword.isNullOrEmpty()) {
                    add(ErrorDetail("currentPassword", "Tạo tài khoản ADMIN phải nhập mật khẩu hiện tại để xác thực lại (BR-A08)"))
                }
            }
            if (missing.isNotEmpty()) throw BusinessException(ErrorCode.VALIDATION_FAILED, details = missing)
            passwordService.verifyCurrentPassword(actorUserId, request.currentPassword)
            reauthenticated = true
        }

        if (userRepository.findByUsernameIgnoreCase(username) != null) throw BusinessException(ErrorCode.USERNAME_TAKEN)

        val user = User(
            username = username,
            passwordHash = passwordService.encodeValidated(requireNotNull(request.password), "password"),
            fullName = fullName,
            role = role,
        ).apply { createdBy = actorUserId }
        // Unique index ux_users_username_lower là hàng rào cuối khi hai Admin tạo cùng lúc (-> 409 USERNAME_TAKEN).
        val saved = userRepository.saveAndFlush(user)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = AuditActions.USER_CREATED,
                entityType = AuditEntityTypes.USERS,
                entityId = saved.id,
                newValue = mapOf(
                    "username" to saved.username,
                    "fullName" to saved.fullName,
                    "role" to saved.role,
                    "status" to saved.status,
                ),
                reason = reason,
                reauthenticated = reauthenticated,
            ),
        )
        return toResponse(saved)
    }

    @Transactional
    fun update(actorUserId: Long, userId: Long, request: UpdateUserRequest): UserResponse {
        val user = load(userId)
        if (request.version != user.version) throw BusinessException(ErrorCode.CONCURRENT_MODIFICATION)
        val newFullName = requireNotNull(request.fullName).trim()
        if (newFullName == user.fullName) return toResponse(user)

        val oldFullName = user.fullName
        user.fullName = newFullName
        val saved = userRepository.saveAndFlush(user)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = AuditActions.USER_UPDATED,
                entityType = AuditEntityTypes.USERS,
                entityId = userId,
                oldValue = mapOf("fullName" to oldFullName),
                newValue = mapOf("fullName" to newFullName),
            ),
        )
        return toResponse(saved)
    }

    /** Khóa / ngừng dùng / mở lại tài khoản (FR-USER-01). */
    @Transactional
    fun changeStatus(actorUserId: Long, userId: Long, request: ChangeUserStatusRequest): UserResponse {
        val target = requireNotNull(request.status)
        val user = load(userId)
        if (user.status == target) {
            throw BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Tài khoản đã ở trạng thái $target")
        }
        if (target != UserStatus.ACTIVE) ensureNotLastActiveAdmin(user)

        val oldStatus = user.status
        user.status = target
        // flush ngay để trigger (LAST_ACTIVE_ADMIN, thu hồi refresh token) chạy trong transaction này.
        val saved = userRepository.saveAndFlush(user)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = when (target) {
                    UserStatus.ACTIVE -> AuditActions.USER_UNLOCKED
                    UserStatus.LOCKED -> AuditActions.USER_LOCKED
                    UserStatus.DISABLED -> AuditActions.USER_DISABLED
                },
                entityType = AuditEntityTypes.USERS,
                entityId = userId,
                oldValue = mapOf("status" to oldStatus),
                newValue = mapOf("status" to target),
                reason = request.reason,
            ),
        )
        return toResponse(saved)
    }

    /**
     * Đổi role (FR-USER-04, BR-A08, BR-A09, SRS §6.17): người thao tác là ADMIN đang hoạt động (controller
     * + filter JWT), xác thực lại bằng mật khẩu hiện tại, lý do bắt buộc, audit USER_ROLE_CHANGED có
     * role cũ/mới và reauthenticated = true. Không đi qua user_permission.
     */
    @Transactional
    fun changeRole(actorUserId: Long, userId: Long, request: ChangeUserRoleRequest): UserResponse {
        val newRole = requireNotNull(request.role)
        val reason = requireNotNull(request.reason).trim()
        passwordService.verifyCurrentPassword(actorUserId, request.currentPassword)

        val user = load(userId)
        if (user.role == newRole) {
            throw BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Người dùng đã có role $newRole")
        }
        if (newRole != UserRole.ADMIN) ensureNotLastActiveAdmin(user)

        val oldRole = user.role
        user.role = newRole
        val saved = userRepository.saveAndFlush(user)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = AuditActions.USER_ROLE_CHANGED,
                entityType = AuditEntityTypes.USERS,
                entityId = userId,
                oldValue = mapOf("role" to oldRole),
                newValue = mapOf("role" to newRole),
                reason = reason,
                reauthenticated = true,
            ),
        )
        return toResponse(saved)
    }

    /**
     * Admin đặt lại mật khẩu cho user khác (FR-USER-02): thu hồi mọi refresh token (PASSWORD_CHANGED).
     * Với tài khoản của chính mình phải dùng đổi mật khẩu (cần mật khẩu hiện tại) để token bị lộ
     * không thể chiếm tài khoản.
     */
    @Transactional
    fun resetPassword(actorUserId: Long, userId: Long, request: ResetPasswordRequest): UserResponse {
        if (actorUserId == userId) {
            throw BusinessException(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                "Không đặt lại mật khẩu cho chính mình; hãy dùng chức năng đổi mật khẩu",
            )
        }
        val user = load(userId)
        user.passwordHash = passwordService.encodeValidated(requireNotNull(request.newPassword), "newPassword")
        user.passwordChangedAt = OffsetDateTime.now(clock)
        val saved = userRepository.saveAndFlush(user)
        refreshTokenService.revokeAllForUser(userId, RevokeReason.PASSWORD_CHANGED)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = AuditActions.USER_PASSWORD_RESET,
                entityType = AuditEntityTypes.USERS,
                entityId = userId,
                reason = request.reason,
            ),
        )
        return toResponse(saved)
    }

    private fun ensureNotLastActiveAdmin(user: User) {
        if (user.role == UserRole.ADMIN && user.status == UserStatus.ACTIVE &&
            userRepository.countByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE) <= 1
        ) {
            throw BusinessException(ErrorCode.LAST_ACTIVE_ADMIN)
        }
    }

    private fun load(userId: Long): User =
        userRepository.findById(userId).orElseThrow { ResourceNotFoundException("người dùng", userId) }

    private fun toResponse(user: User): UserResponse =
        UserResponse.of(user, permissionService.effectivePermissions(requireNotNull(user.id), user.role))
}
