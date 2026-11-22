package com.qrorder.auth.service

import com.qrorder.auth.domain.RevokeReason
import com.qrorder.auth.domain.User
import com.qrorder.auth.dto.ChangePasswordRequest
import com.qrorder.auth.dto.CurrentUserResponse
import com.qrorder.auth.dto.LoginRequest
import com.qrorder.auth.dto.TokenResponse
import com.qrorder.auth.password.PasswordService
import com.qrorder.auth.repository.UserRepository
import com.qrorder.auth.security.JwtService
import com.qrorder.auth.token.IssuedRefreshToken
import com.qrorder.auth.token.RefreshTokenService
import com.qrorder.auth.token.RotationResult
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
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Đăng nhập, refresh, đăng xuất, thông tin người dùng hiện tại, tự đổi mật khẩu
 * (FR-AUTH-01, FR-AUTH-02, FR-USER-02).
 */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordService: PasswordService,
    private val jwtService: JwtService,
    private val refreshTokenService: RefreshTokenService,
    private val permissionService: PermissionService,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Kiểm tra mật khẩu (BCrypt) trước, rồi mới kiểm tra trạng thái tài khoản: người không biết mật khẩu
     * không dò được tài khoản nào bị khóa. Tài khoản LOCKED/DISABLED -> ACCOUNT_DISABLED (401).
     * Thành công: cập nhật last_login_at, phát hành access token + refresh token với family mới.
     */
    @Transactional
    fun login(request: LoginRequest): TokenResponse {
        val username = requireNotNull(request.username).trim()
        val password = requireNotNull(request.password)

        val user = userRepository.findByUsernameIgnoreCase(username)
        if (user == null) {
            passwordService.consumeComparableTime(password)
            log.debug("Đăng nhập thất bại: không tìm thấy tài khoản")
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
        }
        if (!passwordService.matches(password, user.passwordHash)) {
            log.info("Đăng nhập thất bại: sai mật khẩu userId={}", user.id)
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
        }
        if (!user.isActive) {
            log.info("Đăng nhập bị từ chối: tài khoản userId={} đang {}", user.id, user.status)
            throw BusinessException(ErrorCode.ACCOUNT_DISABLED)
        }

        val userId = requireNotNull(user.id)
        userRepository.touchLastLogin(userId)
        val refreshToken = refreshTokenService.issueForLogin(userId)
        // touchLastLogin xóa persistence context: đọc lại để trả last_login_at mới.
        val current = userRepository.findById(userId).orElseThrow { ResourceNotFoundException("người dùng", userId) }
        log.info("Đăng nhập thành công userId={}", userId)
        return tokens(current, refreshToken)
    }

    /**
     * Refresh theo rotation (FR-AUTH-02). KHÔNG chạy trong transaction bao ngoài: [RefreshTokenService.rotate]
     * tự commit, nhờ đó việc thu hồi cả family khi phát hiện dùng lại vẫn được lưu dù request trả 401.
     *
     * - Không tìm thấy token -> 401 REFRESH_TOKEN_INVALID.
     * - Hết hạn -> 401 REFRESH_TOKEN_EXPIRED.
     * - Token đã bị dùng/thu hồi -> cả family đã bị thu hồi (REUSE_DETECTED) -> 401 REFRESH_TOKEN_REUSED;
     *   nếu nguyên nhân là tài khoản đã bị khóa/ngừng dùng thì trả 401 ACCOUNT_DISABLED cho rõ.
     */
    fun refresh(rawRefreshToken: String): TokenResponse =
        when (val result = refreshTokenService.rotate(rawRefreshToken)) {
            RotationResult.Invalid -> throw BusinessException(ErrorCode.REFRESH_TOKEN_INVALID)
            RotationResult.Expired -> throw BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED)
            is RotationResult.ReuseDetected -> {
                val user = userRepository.findById(result.userId).orElse(null)
                if (user == null || !user.isActive) throw BusinessException(ErrorCode.ACCOUNT_DISABLED)
                throw BusinessException(ErrorCode.REFRESH_TOKEN_REUSED)
            }
            is RotationResult.Rotated -> {
                val user = userRepository.findById(result.userId).orElse(null)
                if (user == null || !user.isActive) {
                    // Trường hợp hiếm: tài khoản bị khóa giữa lúc rotation; thu hồi luôn token vừa cấp.
                    refreshTokenService.logout(result.token.rawToken)
                    throw BusinessException(ErrorCode.ACCOUNT_DISABLED)
                }
                tokens(user, result.token)
            }
        }

    /** Đăng xuất: thu hồi family của refresh token (LOGOUT). Idempotent: token lạ/đã thu hồi vẫn trả thành công. */
    fun logout(rawRefreshToken: String) {
        val revoked = refreshTokenService.logout(rawRefreshToken)
        log.debug("Đăng xuất: thu hồi {} refresh token", revoked)
    }

    @Transactional(readOnly = true)
    fun me(userId: Long): CurrentUserResponse {
        val user = userRepository.findById(userId).orElseThrow { ResourceNotFoundException("người dùng", userId) }
        return CurrentUserResponse.of(user, permissionService.effectivePermissions(userId, user.role))
    }

    /**
     * Tự đổi mật khẩu (FR-USER-02): kiểm tra mật khẩu hiện tại, chính sách mật khẩu, mật khẩu mới phải khác;
     * thu hồi mọi refresh token (PASSWORD_CHANGED) - mọi thiết bị phải đăng nhập lại; audit USER_PASSWORD_CHANGED.
     */
    @Transactional
    fun changePassword(userId: Long, request: ChangePasswordRequest) {
        val newPassword = requireNotNull(request.newPassword)
        val user = passwordService.verifyCurrentPassword(userId, request.currentPassword)
        if (passwordService.matches(newPassword, user.passwordHash)) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                details = listOf(ErrorDetail("newPassword", "Mật khẩu mới phải khác mật khẩu hiện tại")),
            )
        }
        user.passwordHash = passwordService.encodeValidated(newPassword, "newPassword")
        user.passwordChangedAt = OffsetDateTime.now(clock)
        userRepository.saveAndFlush(user)
        val revoked = refreshTokenService.revokeAllForUser(userId, RevokeReason.PASSWORD_CHANGED)
        auditService.record(
            AuditActor.User(userId),
            AuditEvent(
                action = AuditActions.USER_PASSWORD_CHANGED,
                entityType = AuditEntityTypes.USERS,
                entityId = userId,
            ),
        )
        log.info("userId={} đổi mật khẩu; thu hồi {} refresh token", userId, revoked)
    }

    private fun tokens(user: User, refreshToken: IssuedRefreshToken): TokenResponse {
        val userId = requireNotNull(user.id)
        val access = jwtService.issueStaffAccessToken(userId, user.username, user.role)
        return TokenResponse(
            accessToken = access.token,
            accessTokenExpiresAt = access.expiresAt,
            refreshToken = refreshToken.rawToken,
            refreshTokenExpiresAt = refreshToken.expiresAt,
            user = CurrentUserResponse.of(user, permissionService.effectivePermissions(userId, user.role)),
        )
    }
}
