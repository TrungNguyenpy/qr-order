package com.qrorder.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.security.JwtService
import com.qrorder.auth.token.TokenHasher
import com.qrorder.support.ApiTestClient
import com.qrorder.support.ApiTestClient.Companion.DEFAULT_PASSWORD
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.IntegrationTestBase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * API xác thực (FR-AUTH-01, FR-AUTH-02, FR-USER-02) qua HTTP thật + PostgreSQL thật (Testcontainers),
 * mọi thao tác ghi COMMIT thật.
 */
@AutoConfigureMockMvc
class AuthApiIT : IntegrationTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var passwordEncoder: PasswordEncoder
    @Autowired lateinit var tokenHasher: TokenHasher
    @Autowired lateinit var jwtService: JwtService

    private val api by lazy { ApiTestClient(mvc, objectMapper, jdbc, passwordEncoder) }

    // ------------------------------------------------------------------ login

    @Test
    fun `login succeeds with BCrypt password, updates last_login_at and opens a new refresh token family`() {
        val id = api.createUser("thu_ngan", UserRole.STAFF)
        assertNull(jdbc.queryForObject("SELECT last_login_at FROM users WHERE id = ?", OffsetDateTime::class.java, id))
        val versionBefore = fx.count("SELECT version FROM users WHERE id = ?", id)

        val body = api.json(api.login("Thu_Ngan", DEFAULT_PASSWORD).andExpect(status().isOk)) // username không phân biệt hoa thường

        val data = body["data"]
        assertTrue(body["success"].asBoolean())
        assertEquals("Bearer", data["tokenType"].asText())
        assertTrue(data["accessToken"].asText().isNotBlank())
        assertTrue(data["refreshToken"].asText().isNotBlank())
        assertNotNull(data["accessTokenExpiresAt"])
        assertNotNull(data["refreshTokenExpiresAt"])
        assertEquals(id, data["user"]["id"].asLong())
        assertEquals("STAFF", data["user"]["role"].asText())
        assertEquals(0, data["user"]["permissions"].size())
        assertNotNull(data["user"]["lastLoginAt"].takeIf { !it.isNull })
        assertFalse(body.toString().contains("passwordHash"), "Không được trả password_hash")
        assertFalse(body.toString().contains(DEFAULT_PASSWORD), "Không được trả mật khẩu")

        assertNotNull(jdbc.queryForObject("SELECT last_login_at FROM users WHERE id = ?", OffsetDateTime::class.java, id))
        // last_login_at ghi bằng câu UPDATE riêng: không tăng version (không phá optimistic lock của Admin).
        assertEquals(versionBefore, fx.count("SELECT version FROM users WHERE id = ?", id))
        assertEquals(1L, api.activeRefreshTokens(id))
        val hash = tokenHasher.sha256Hex(data["refreshToken"].asText())
        assertEquals(1L, fx.count("SELECT count(*) FROM refresh_token WHERE token_hash = ? AND user_id = ?", hash, id))

        api.get("/api/v1/auth/me", data["accessToken"].asText())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.username").value("thu_ngan"))
    }

    @Test
    fun `each login opens its own family so other devices stay signed in`() {
        val id = api.createUser("hai_may", UserRole.STAFF)
        val first = api.loginData("hai_may", DEFAULT_PASSWORD)
        val second = api.loginData("hai_may", DEFAULT_PASSWORD)
        assertEquals(2L, api.activeRefreshTokens(id))
        assertEquals(2L, fx.count("SELECT count(DISTINCT family_id) FROM refresh_token WHERE user_id = ?", id))
        assertNotEquals(first["refreshToken"].asText(), second["refreshToken"].asText())
    }

    @Test
    fun `wrong password and unknown username return the same 401 INVALID_CREDENTIALS`() {
        api.createUser("phuc_vu", UserRole.STAFF)
        val wrong = api.json(api.login("phuc_vu", "SaiMatKhau1").isError(401, "INVALID_CREDENTIALS"))
        val unknown = api.json(api.login("khong_ton_tai", DEFAULT_PASSWORD).isError(401, "INVALID_CREDENTIALS"))
        assertEquals(wrong["error"]["message"], unknown["error"]["message"])
        assertEquals(0L, fx.count("SELECT count(*) FROM refresh_token"))
    }

    @Test
    fun `LOCKED or DISABLED account cannot log in (ACCOUNT_DISABLED) but wrong password still says INVALID_CREDENTIALS`() {
        api.createUser("bi_khoa", UserRole.STAFF, status = "LOCKED")
        api.createUser("nghi_viec", UserRole.KITCHEN, status = "DISABLED")

        api.login("bi_khoa", DEFAULT_PASSWORD).isError(401, "ACCOUNT_DISABLED")
        api.login("nghi_viec", DEFAULT_PASSWORD).isError(401, "ACCOUNT_DISABLED")
        // Không biết mật khẩu thì không dò được trạng thái tài khoản.
        api.login("bi_khoa", "SaiMatKhau1").isError(401, "INVALID_CREDENTIALS")
        assertEquals(0L, fx.count("SELECT count(*) FROM refresh_token"))
        assertEquals(0L, fx.count("SELECT count(*) FROM users WHERE last_login_at IS NOT NULL"))
    }

    @Test
    fun `login with missing fields returns 400 VALIDATION_FAILED`() {
        api.post("/api/v1/auth/login", null, mapOf("username" to " ", "password" to ""))
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details.length()").value(2))
    }

    @Test
    fun `password hash that is not in DelegatingPasswordEncoder format is treated as a mismatch, not a 500`() {
        fx.user("hash_cu", UserRole.STAFF) // password_hash = 'x'
        api.login("hash_cu", DEFAULT_PASSWORD).isError(401, "INVALID_CREDENTIALS")
    }

    // ------------------------------------------------------------------ refresh

    @Test
    fun `refresh rotates the token, reusing the old token revokes the whole family (401 REFRESH_TOKEN_REUSED)`() {
        val user = api.loggedIn("xoay_vong", UserRole.STAFF)

        val rotated = api.data(api.refresh(user.refreshToken).andExpect(status().isOk))
        val newRefresh = rotated["refreshToken"].asText()
        assertNotEquals(user.refreshToken, newRefresh)
        assertTrue(rotated["accessToken"].asText().isNotBlank())
        assertEquals(1L, api.activeRefreshTokens(user.id))

        // Dùng lại token cũ (đã ROTATED) -> phát hiện reuse, thu hồi cả family, kể cả token vừa cấp.
        api.refresh(user.refreshToken).isError(401, "REFRESH_TOKEN_REUSED")
        assertEquals(0L, api.activeRefreshTokens(user.id))
        assertTrue("REUSE_DETECTED" in api.revokeReasons(user.id))
        // Việc thu hồi đã được COMMIT dù request trả 401: token mới cũng không dùng được nữa.
        api.refresh(newRefresh).isError(401, "REFRESH_TOKEN_REUSED")
    }

    @Test
    fun `unknown refresh token returns 401 REFRESH_TOKEN_INVALID`() {
        api.refresh("khong-phai-token-that").isError(401, "REFRESH_TOKEN_INVALID")
        api.post("/api/v1/auth/refresh", null, mapOf("refreshToken" to "")).isError(400, "VALIDATION_FAILED")
    }

    @Test
    fun `expired refresh token returns 401 REFRESH_TOKEN_EXPIRED`() {
        val id = api.createUser("het_han", UserRole.STAFF)
        val raw = "expired-" + UUID.randomUUID()
        jdbc.update(
            """INSERT INTO refresh_token (user_id, family_id, token_hash, issued_at, expires_at)
               VALUES (?, ?, ?, now() - interval '10 days', now() - interval '3 days')""",
            id, UUID.randomUUID(), tokenHasher.sha256Hex(raw),
        )
        api.refresh(raw).isError(401, "REFRESH_TOKEN_EXPIRED")
    }

    @Test
    fun `refresh after the account is locked returns 401 ACCOUNT_DISABLED (trigger already revoked the tokens)`() {
        val user = api.loggedIn("sap_bi_khoa", UserRole.STAFF)
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", user.id)
        assertEquals(listOf("USER_LOCKED"), api.revokeReasons(user.id))

        api.refresh(user.refreshToken).isError(401, "ACCOUNT_DISABLED")
    }

    // ------------------------------------------------------------------ logout

    @Test
    fun `logout revokes the current family with LOGOUT, leaves other devices signed in, and is idempotent`() {
        val user = api.loggedIn("dang_xuat", UserRole.STAFF)
        val otherDevice = api.loginData("dang_xuat", DEFAULT_PASSWORD)["refreshToken"].asText()

        api.post("/api/v1/auth/logout", null, mapOf("refreshToken" to user.refreshToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        assertEquals(
            "LOGOUT",
            jdbc.queryForObject(
                "SELECT revoke_reason FROM refresh_token WHERE token_hash = ?", String::class.java,
                tokenHasher.sha256Hex(user.refreshToken),
            ),
        )
        api.refresh(user.refreshToken).isError(401, "REFRESH_TOKEN_REUSED")
        api.refresh(otherDevice).andExpect(status().isOk) // family khác không bị ảnh hưởng

        // Gọi lại / token lạ: vẫn thành công, không lộ token có tồn tại hay không.
        api.post("/api/v1/auth/logout", null, mapOf("refreshToken" to user.refreshToken)).andExpect(status().isOk)
        api.post("/api/v1/auth/logout", null, mapOf("refreshToken" to "token-la")).andExpect(status().isOk)
    }

    // ------------------------------------------------------------------ me

    @Test
    fun `me returns effective permissions - MANAGER has all 8 by default, STAFF only granted ones, ADMIN none by default`() {
        val manager = api.loggedIn("quan_ly", UserRole.MANAGER)
        api.get("/api/v1/auth/me", manager.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.role").value("MANAGER"))
            .andExpect(jsonPath("$.data.permissions.length()").value(Permission.entries.size))

        val admin = api.loggedIn("quan_tri", UserRole.ADMIN)
        api.get("/api/v1/auth/me", admin.accessToken)
            .andExpect(jsonPath("$.data.permissions.length()").value(0)) // BR-A06: không kế thừa MANAGER

        val staff = api.loggedIn("thu_ngan", UserRole.STAFF)
        fx.grant(staff.id, Permission.PAYMENT_PROCESS, admin.id)
        api.get("/api/v1/auth/me", staff.accessToken)
            .andExpect(jsonPath("$.data.permissions.length()").value(1))
            .andExpect(jsonPath("$.data.permissions[0]").value("PAYMENT_PROCESS"))

        val kitchen = api.loggedIn("bep", UserRole.KITCHEN)
        api.get("/api/v1/auth/me", kitchen.accessToken).andExpect(status().isOk)
    }

    @Test
    fun `me requires a staff token`() {
        api.get("/api/v1/auth/me", null).isError(401, "UNAUTHENTICATED")
        val guest = jwtService.issueGuestToken(fx.guestSession("T01"), "device-0001", Duration.ofMinutes(15)).token
        api.get("/api/v1/auth/me", guest).isError(403, "PERMISSION_DENIED")
    }

    // ------------------------------------------------------------------ change password

    @Test
    fun `change password - wrong current password is 403 REAUTHENTICATION_FAILED, weak or same password is 400`() {
        val user = api.loggedIn("doi_mk", UserRole.STAFF)
        val path = "/api/v1/auth/change-password"

        api.post(path, user.accessToken, mapOf("currentPassword" to "SaiMatKhau1", "newPassword" to "MatKhauMoi9"))
            .isError(403, "REAUTHENTICATION_FAILED")
        api.post(path, user.accessToken, mapOf("currentPassword" to DEFAULT_PASSWORD, "newPassword" to "yeu"))
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[0].field").value("newPassword"))
        api.post(path, user.accessToken, mapOf("currentPassword" to DEFAULT_PASSWORD, "newPassword" to "matkhaumoi9"))
            .isError(400, "VALIDATION_FAILED") // thiếu chữ hoa
        api.post(path, user.accessToken, mapOf("currentPassword" to DEFAULT_PASSWORD, "newPassword" to DEFAULT_PASSWORD))
            .isError(400, "VALIDATION_FAILED") // trùng mật khẩu hiện tại

        assertEquals(1L, api.activeRefreshTokens(user.id))
        assertTrue(api.auditRows("USER_PASSWORD_CHANGED").isEmpty())
    }

    @Test
    fun `change password succeeds, revokes every refresh token (PASSWORD_CHANGED) and writes audit`() {
        val user = api.loggedIn("doi_mk_ok", UserRole.STAFF)
        val newPassword = "MatKhauMoi9"

        api.post(
            "/api/v1/auth/change-password", user.accessToken,
            mapOf("currentPassword" to DEFAULT_PASSWORD, "newPassword" to newPassword),
        ).andExpect(status().isOk)

        assertEquals(0L, api.activeRefreshTokens(user.id))
        assertEquals(listOf("PASSWORD_CHANGED"), api.revokeReasons(user.id))
        api.refresh(user.refreshToken).isError(401, "REFRESH_TOKEN_REUSED")
        api.login("doi_mk_ok", DEFAULT_PASSWORD).isError(401, "INVALID_CREDENTIALS")
        api.login("doi_mk_ok", newPassword).andExpect(status().isOk)
        assertNotNull(
            jdbc.queryForObject("SELECT password_changed_at FROM users WHERE id = ?", OffsetDateTime::class.java, user.id),
        )

        val audit = api.auditRows("USER_PASSWORD_CHANGED").single()
        assertEquals("USER", audit["actor_type"])
        assertEquals(user.id, (audit["actor_user_id"] as Number).toLong())
        assertEquals("users", audit["entity_type"])
        assertEquals(user.id, (audit["entity_id"] as Number).toLong())
        assertNull(audit["new_value"], "Không ghi mật khẩu/hash vào audit")
    }
}
