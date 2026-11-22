package com.qrorder.auth.password

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Chính sách mật khẩu Giai đoạn 1: >= 8 ký tự, ít nhất 1 chữ hoa, 1 chữ thường, 1 chữ số (+ tối đa 72 byte cho BCrypt). */
class PasswordPolicyTest {

    @Test
    fun `accepts passwords meeting all rules, including the exact minimum length`() {
        listOf("Matkhau1", "Abcdefg1", "Quản Trị 2026", "ĐăngNhập9", "A1" + "b".repeat(6)).forEach {
            assertTrue(PasswordPolicy.isValid(it), "Phải hợp lệ: $it")
        }
    }

    @Test
    fun `rejects passwords missing a rule`() {
        mapOf(
            "Abcde12" to "7 ký tự",
            "matkhau123" to "thiếu chữ hoa",
            "MATKHAU123" to "thiếu chữ thường",
            "MatKhauDai" to "thiếu chữ số",
            "" to "rỗng",
            "        " to "toàn khoảng trắng",
        ).forEach { (password, why) ->
            assertFalse(PasswordPolicy.isValid(password), "Phải bị từ chối ($why)")
            assertEquals(listOf(PasswordPolicy.MESSAGE), PasswordPolicy.violations(password))
        }
        assertFalse(PasswordPolicy.isValid(null))
    }

    @Test
    fun `rejects passwords longer than 72 UTF-8 bytes (BCrypt limit)`() {
        val exactly72 = "Aa1" + "x".repeat(69)
        assertTrue(PasswordPolicy.isValid(exactly72))
        val seventyThree = exactly72 + "y"
        assertEquals(listOf(PasswordPolicy.TOO_LONG_MESSAGE), PasswordPolicy.violations(seventyThree))
        // Ký tự tiếng Việt chiếm nhiều byte: 30 ký tự "ệ" = 90 byte.
        assertFalse(PasswordPolicy.isValid("Aa1" + "ệ".repeat(30)))
    }

    @Test
    fun `violation messages never contain the password`() {
        val secret = "bimat"
        PasswordPolicy.violations(secret).forEach { assertFalse(it.contains(secret)) }
    }
}
