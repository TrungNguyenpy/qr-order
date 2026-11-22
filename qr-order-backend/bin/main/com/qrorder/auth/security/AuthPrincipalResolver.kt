package com.qrorder.auth.security

import com.qrorder.auth.domain.UserStatus
import com.qrorder.auth.repository.UserRepository
import com.qrorder.common.error.ErrorCode
import org.springframework.stereotype.Component

/**
 * Từ chuỗi JWT ra principal đã xác thực - dùng chung cho REST filter và STOMP CONNECT.
 *
 * BR-A04 (khóa tài khoản, đổi role có hiệu lực ngay): với Staff, đối chiếu mỗi lần với bảng users
 * (một truy vấn theo khóa chính): tài khoản không còn ACTIVE -> ACCOUNT_DISABLED; role trong token
 * khác role hiện tại -> TOKEN_INVALID (client phải đăng nhập/refresh để nhận token mới).
 * Guest (BE-05, FR-AUTH-04, BR-S03 - Giai đoạn 3): đối chiếu mỗi lần với table_session/session_device qua
 * [GuestSessionGate]: phiên không còn OPEN/BILLING -> SESSION_CLOSED (Guest JWT vô hiệu ngay khi phiên đóng, không
 * chờ hết TTL); thiết bị không thuộc phiên -> TOKEN_INVALID.
 */
@Component
class AuthPrincipalResolver(
    private val jwtService: JwtService,
    private val userRepository: UserRepository,
    private val guestSessionGate: GuestSessionGate,
) {
    fun resolve(token: String): AuthPrincipal {
        val principal = jwtService.parse(token)
        if (principal is StaffPrincipal) {
            val snapshot = userRepository.findAuthSnapshot(principal.userId)
                ?: throw AuthTokenException(ErrorCode.TOKEN_INVALID)
            if (snapshot.status != UserStatus.ACTIVE) throw AuthTokenException(ErrorCode.ACCOUNT_DISABLED)
            if (snapshot.role != principal.role) throw AuthTokenException(ErrorCode.TOKEN_INVALID)
        }
        if (principal is GuestPrincipal) {
            when (guestSessionGate.check(principal.sessionId, principal.deviceId)) {
                GuestSessionCheck.ACTIVE -> Unit
                GuestSessionCheck.SESSION_CLOSED -> throw AuthTokenException(ErrorCode.SESSION_CLOSED)
                GuestSessionCheck.DEVICE_UNKNOWN -> throw AuthTokenException(ErrorCode.TOKEN_INVALID)
            }
        }
        return principal
    }
}
