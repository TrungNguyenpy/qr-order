package com.qrorder.auth.password

import com.qrorder.auth.domain.User
import com.qrorder.auth.repository.UserRepository
import com.qrorder.common.api.ErrorDetail
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Băm/kiểm tra mật khẩu và xác thực lại (re-authentication) cho các thao tác nhạy cảm
 * (BR-A07 tự cấp/thu hồi permission, BR-A08 đổi role, tạo ADMIN, đổi mật khẩu).
 *
 * Không bao giờ ghi mật khẩu hay password_hash ra log/response (NFR-04, quy tắc 7).
 */
@Service
class PasswordService(
    private val encoder: PasswordEncoder,
    private val userRepository: UserRepository,
) {
    /** Hash giả để thời gian xử lý khi username không tồn tại tương đương khi sai mật khẩu (chống dò tài khoản). */
    private val dummyHash: String by lazy { encoder.encode(UUID.randomUUID().toString()) }

    /** Băm mật khẩu sau khi kiểm tra chính sách; [field] là tên trường trả về trong lỗi validation. */
    fun encodeValidated(raw: String, field: String = "password"): String {
        assertPolicy(raw, field)
        return encoder.encode(raw)
    }

    fun assertPolicy(raw: String?, field: String = "password") {
        val violations = PasswordPolicy.violations(raw)
        if (violations.isNotEmpty()) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                details = violations.map { ErrorDetail(field, it) },
            )
        }
    }

    /**
     * So khớp an toàn: hash không đúng định dạng của DelegatingPasswordEncoder (thiếu tiền tố {id})
     * hoặc mật khẩu quá 72 byte làm encoder ném IllegalArgumentException - coi như không khớp.
     */
    fun matches(raw: String?, hash: String?): Boolean {
        if (raw == null || hash == null) return false
        return try {
            encoder.matches(raw, hash)
        } catch (ex: IllegalArgumentException) {
            false
        }
    }

    /** Tiêu tốn thời gian tương đương một lần so khớp BCrypt (dùng khi username không tồn tại). */
    fun consumeComparableTime(raw: String?) {
        matches(raw ?: "", dummyHash)
    }

    /**
     * Xác thực lại người đang thao tác bằng mật khẩu hiện tại; trả về User đã kiểm tra.
     * Sai mật khẩu -> REAUTHENTICATION_FAILED (403).
     */
    fun verifyCurrentPassword(userId: Long, currentPassword: String?): User {
        val user = userRepository.findById(userId).orElseThrow { ResourceNotFoundException("người dùng", userId) }
        if (!matches(currentPassword, user.passwordHash)) throw BusinessException(ErrorCode.REAUTHENTICATION_FAILED)
        return user
    }
}
