package com.qrorder.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.qrorder.auth.domain.UserRole
import com.qrorder.support.ApiTestClient
import com.qrorder.support.ApiTestClient.Companion.DEFAULT_PASSWORD
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.IntegrationTestBase
import com.qrorder.support.LoggedInUser
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Cấp/thu hồi permission (FR-USER-03, BR-A04, BR-A06..A08) và SRS §12.4 #4, #7.
 * Endpoint /api/v1/test/payment (src/test) yêu cầu @perm.has('PAYMENT_PROCESS') để kiểm tra hiệu lực tức thì.
 */
@AutoConfigureMockMvc
class PermissionManagementIT : IntegrationTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val api by lazy { ApiTestClient(mvc, objectMapper, jdbc, passwordEncoder) }
    private lateinit var admin: LoggedInUser

    @BeforeEach
    fun setUp() {
        admin = api.loggedIn("admin1", UserRole.ADMIN)
    }

    private fun grantPath(userId: Long) = "/api/v1/users/$userId/permissions"
    private fun roleOf(id: Long): String = jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String::class.java, id)!!

    @Test
    fun `ADMIN grants PAYMENT_PROCESS to a STAFF - effective immediately, refresh tokens revoked (PERMISSION_CHANGED), audit`() {
        val cashier = api.loggedIn("thu_ngan", UserRole.STAFF)
        api.get("/api/v1/test/payment", cashier.accessToken).isError(403, "PERMISSION_DENIED")

        val granted = api.data(
            api.post(grantPath(cashier.id), admin.accessToken, mapOf("permission" to "PAYMENT_PROCESS", "reason" to "Thu ngân ca tối"))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.permission").value("PAYMENT_PROCESS"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.grantedBy").value(admin.id))
                .andExpect(jsonPath("$.data.reauthenticated").value(false)),
        )

        // Quyền đọc từ database mỗi request: access token cũ dùng được ngay (BR-A04).
        api.get("/api/v1/test/payment", cashier.accessToken).andExpect(status().isOk)
        api.get("/api/v1/auth/me", cashier.accessToken).andExpect(jsonPath("$.data.permissions[0]").value("PAYMENT_PROCESS"))
        assertEquals(listOf("PERMISSION_CHANGED"), api.revokeReasons(cashier.id))
        api.refresh(cashier.refreshToken).isError(401, "REFRESH_TOKEN_REUSED")

        val audit = api.auditRows("PERMISSION_GRANTED").single()
        assertEquals(admin.id, (audit["actor_user_id"] as Number).toLong())
        assertEquals("user_permission", audit["entity_type"])
        assertEquals(granted["id"].asLong(), (audit["entity_id"] as Number).toLong())
        assertEquals("Thu ngân ca tối", audit["reason"])
        val newValue = api.jsonOf(audit["new_value"])
        assertEquals(cashier.id, newValue["userId"].asLong())
        assertEquals("PAYMENT_PROCESS", newValue["permission"].asText())
        assertEquals(false, newValue["selfGrant"].asBoolean())
    }

    @Test
    fun `grant validation - reason required, duplicate is 409, MANAGER already has all permissions, non-admin gets 403`() {
        val staff = api.createUser("nv_1", UserRole.STAFF)
        val manager = api.loggedIn("ql_1", UserRole.MANAGER)

        api.post(grantPath(staff), admin.accessToken, mapOf("permission" to "REPORT_VIEW"))
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[0].field").value("reason"))
        api.post(grantPath(staff), admin.accessToken, mapOf("permission" to "REPORT_VIEW", "reason" to "Xem báo cáo"))
            .andExpect(status().isCreated)
        api.post(grantPath(staff), admin.accessToken, mapOf("permission" to "REPORT_VIEW", "reason" to "Lần hai"))
            .isError(409, "PERMISSION_ALREADY_GRANTED")
        api.post(grantPath(manager.id), admin.accessToken, mapOf("permission" to "REPORT_VIEW", "reason" to "x"))
            .isError(422, "BUSINESS_RULE_VIOLATION")
        api.post(grantPath(staff), manager.accessToken, mapOf("permission" to "BILL_VOID", "reason" to "x"))
            .isError(403, "PERMISSION_DENIED")
        api.post(grantPath(999999), admin.accessToken, mapOf("permission" to "BILL_VOID", "reason" to "x"))
            .isError(404, "RESOURCE_NOT_FOUND")
        assertEquals(1L, fx.count("SELECT count(*) FROM user_permission"))
    }

    @Test
    fun `revoke keeps history, takes effect immediately, revokes refresh tokens and writes audit`() {
        val cashier = api.loggedIn("thu_ngan", UserRole.STAFF)
        api.post(grantPath(cashier.id), admin.accessToken, mapOf("permission" to "PAYMENT_PROCESS", "reason" to "Ca tối"))
            .andExpect(status().isCreated)
        val token = api.loginData(cashier.username, DEFAULT_PASSWORD)["accessToken"].asText()
        api.get("/api/v1/test/payment", token).andExpect(status().isOk)

        api.post("${grantPath(cashier.id)}/PAYMENT_PROCESS/revoke", admin.accessToken, mapOf("reason" to "Hết ca"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.active").value(false))
            .andExpect(jsonPath("$.data.revokedBy").value(admin.id))
            .andExpect(jsonPath("$.data.revokeReason").value("Hết ca"))

        api.get("/api/v1/test/payment", token).isError(403, "PERMISSION_DENIED")
        assertEquals(0L, api.activeRefreshTokens(cashier.id))
        val audit = api.auditRows("PERMISSION_REVOKED").single()
        assertEquals("Hết ca", audit["reason"])
        assertEquals(false, api.jsonOf(audit["new_value"])["active"].asBoolean())

        // Lịch sử giữ lại (không xóa), chỉ hiện khi includeRevoked=true.
        api.get(grantPath(cashier.id), admin.accessToken).andExpect(jsonPath("$.data.length()").value(0))
        api.get("${grantPath(cashier.id)}?includeRevoked=true", admin.accessToken)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].active").value(false))

        api.post("${grantPath(cashier.id)}/PAYMENT_PROCESS/revoke", admin.accessToken, mapOf("reason" to "Lần hai"))
            .isError(409, "PERMISSION_NOT_GRANTED")
        api.post("${grantPath(cashier.id)}/PAYMENT_PROCESS/revoke", admin.accessToken, mapOf<String, Any>())
            .isError(400, "VALIDATION_FAILED")
        api.post("${grantPath(cashier.id)}/KHONG_CO/revoke", admin.accessToken, mapOf("reason" to "x"))
            .isError(400, "MALFORMED_REQUEST")
        // Cấp lại sau khi thu hồi được phép (unique chỉ áp dụng bản cấp còn hiệu lực).
        api.post(grantPath(cashier.id), admin.accessToken, mapOf("permission" to "PAYMENT_PROCESS", "reason" to "Ca sáng"))
            .andExpect(status().isCreated)
    }

    @Test
    fun `SRS 12_4 #7 - ADMIN self-grants a permission with re-authentication and reason, audited, role unchanged`() {
        val path = grantPath(admin.id)
        api.get("/api/v1/test/payment", admin.accessToken).isError(403, "PERMISSION_DENIED") // BR-A06

        api.post(path, admin.accessToken, mapOf("permission" to "PAYMENT_PROCESS", "reason" to "Chỉ có 1 quản trị"))
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[0].field").value("currentPassword"))
        api.post(
            path, admin.accessToken,
            mapOf("permission" to "PAYMENT_PROCESS", "reason" to "Chỉ có 1 quản trị", "currentPassword" to "SaiMatKhau1"),
        ).isError(403, "REAUTHENTICATION_FAILED")
        assertEquals(0L, fx.count("SELECT count(*) FROM user_permission"))

        api.post(
            path, admin.accessToken,
            mapOf("permission" to "PAYMENT_PROCESS", "reason" to "Chỉ có 1 quản trị", "currentPassword" to DEFAULT_PASSWORD),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.data.reauthenticated").value(true))

        assertEquals(1L, fx.count("SELECT count(*) FROM user_permission WHERE user_id = granted_by AND reauthenticated"))
        val audit = api.auditRows("PERMISSION_GRANTED").single()
        assertEquals(true, audit["reauthenticated"])
        assertEquals("Chỉ có 1 quản trị", audit["reason"])
        assertEquals(true, api.jsonOf(audit["new_value"])["selfGrant"].asBoolean())
        assertEquals("ADMIN", roleOf(admin.id)) // không đổi role
        api.get("/api/v1/test/payment", admin.accessToken).andExpect(status().isOk)

        // Tự thu hồi cũng phải xác thực lại (BR-A07).
        api.post("$path/PAYMENT_PROCESS/revoke", admin.accessToken, mapOf("reason" to "Đã có thu ngân"))
            .isError(400, "VALIDATION_FAILED")
        api.post("$path/PAYMENT_PROCESS/revoke", admin.accessToken, mapOf("reason" to "Đã có thu ngân", "currentPassword" to DEFAULT_PASSWORD))
            .andExpect(status().isOk)
        assertEquals(true, api.auditRows("PERMISSION_REVOKED").single()["reauthenticated"])
    }

    @Test
    fun `granting another user with currentPassword records reauthenticated = true`() {
        val staff = api.createUser("nv_1", UserRole.STAFF)
        api.post(
            grantPath(staff), admin.accessToken,
            mapOf("permission" to "SESSION_REOPEN", "reason" to "Hỗ trợ ca", "currentPassword" to DEFAULT_PASSWORD),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.data.reauthenticated").value(true))
        api.post(
            grantPath(staff), admin.accessToken,
            mapOf("permission" to "REPORT_VIEW", "reason" to "x", "currentPassword" to "SaiMatKhau1"),
        ).isError(403, "REAUTHENTICATION_FAILED")
    }

    @Test
    fun `SRS 12_4 #4 - the permission mechanism cannot make anyone ADMIN`() {
        val staff = api.loggedIn("muon_admin", UserRole.STAFF)
        val json = """{"permission":"ADMIN","reason":"Cấp quyền quản trị","currentPassword":"$DEFAULT_PASSWORD"}"""
        api.postRaw(grantPath(staff.id), admin.accessToken, json).isError(400, "MALFORMED_REQUEST")
        api.postRaw(grantPath(admin.id), admin.accessToken, json).isError(400, "MALFORMED_REQUEST")
        // STAFF không gọi được API cấp quyền, kể cả cho chính mình. (Body được đọc trước @PreAuthorize, nên dùng
        // body hợp lệ để thấy 403; body "ADMIN" ở trên trả 400 cho mọi người gọi.)
        api.post(
            grantPath(staff.id), staff.accessToken,
            mapOf("permission" to "PAYMENT_PROCESS", "reason" to "Tự cấp", "currentPassword" to DEFAULT_PASSWORD),
        ).isError(403, "PERMISSION_DENIED")

        // Kể cả khi có đủ 8 permission, role vẫn là STAFF và không vào được API của ADMIN.
        listOf(
            "PAYMENT_PROCESS", "BILL_DISCOUNT", "BILL_VOID", "REFUND_RECORD",
            "ITEM_CANCEL_APPROVE", "SESSION_FORCE_CLOSE", "SESSION_REOPEN", "REPORT_VIEW",
        ).forEach {
            api.post(grantPath(staff.id), admin.accessToken, mapOf("permission" to it, "reason" to "Đủ quyền")).andExpect(status().isCreated)
        }
        assertEquals("STAFF", roleOf(staff.id))
        val token = api.loginData(staff.username, DEFAULT_PASSWORD)["accessToken"].asText()
        api.get("/api/v1/users", token).isError(403, "PERMISSION_DENIED")
        api.get("/api/v1/settings", token).isError(403, "PERMISSION_DENIED")
        assertTrue(api.auditRows("USER_ROLE_CHANGED").isEmpty())
    }
}
