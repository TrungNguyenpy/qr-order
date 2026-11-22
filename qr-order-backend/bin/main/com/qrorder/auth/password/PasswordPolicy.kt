package com.qrorder.auth.password

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * Chính sách mật khẩu Giai đoạn 1 (người dùng chốt 20/11/2026):
 * tối thiểu 8 ký tự, ít nhất 1 chữ hoa, 1 chữ thường và 1 chữ số.
 *
 * Thêm giới hạn kỹ thuật tối đa 72 byte UTF-8: BCrypt chỉ dùng 72 byte đầu và từ Spring Security 6.3
 * `BCryptPasswordEncoder` từ chối mật khẩu dài hơn (ném IllegalArgumentException).
 *
 * Áp dụng ở mọi nơi đặt mật khẩu: tạo user, reset bởi Admin, tự đổi mật khẩu, khởi tạo ADMIN đầu tiên.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val MAX_BYTES = 72
    const val MESSAGE = "Mật khẩu phải có tối thiểu 8 ký tự, gồm ít nhất 1 chữ hoa, 1 chữ thường và 1 chữ số"
    const val TOO_LONG_MESSAGE = "Mật khẩu không được dài quá 72 byte"

    /** Danh sách vi phạm (rỗng = hợp lệ). Không bao giờ đưa giá trị mật khẩu vào thông điệp. */
    fun violations(password: String?): List<String> {
        if (password == null) return listOf(MESSAGE)
        val result = mutableListOf<String>()
        val strongEnough = password.length >= MIN_LENGTH &&
            password.any { it.isUpperCase() } &&
            password.any { it.isLowerCase() } &&
            password.any { it.isDigit() }
        if (!strongEnough) result += MESSAGE
        if (password.toByteArray(Charsets.UTF_8).size > MAX_BYTES) result += TOO_LONG_MESSAGE
        return result
    }

    fun isValid(password: String?): Boolean = violations(password).isEmpty()
}

/** Bean Validation cho [PasswordPolicy]. null được coi là hợp lệ (kết hợp @NotBlank/@NotNull khi bắt buộc). */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [StrongPasswordValidator::class])
annotation class StrongPassword(
    val message: String = PasswordPolicy.MESSAGE,
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class StrongPasswordValidator : ConstraintValidator<StrongPassword, String?> {
    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        val violations = PasswordPolicy.violations(value)
        if (violations.isEmpty()) return true
        context.disableDefaultConstraintViolation()
        violations.forEach { context.buildConstraintViolationWithTemplate(it).addConstraintViolation() }
        return false
    }
}
