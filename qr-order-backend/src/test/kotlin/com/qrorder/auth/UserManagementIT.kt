package com.qrorder.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.dto.ChangeUserRoleRequest
import com.qrorder.auth.service.UserService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.support.ApiTestClient
import com.qrorder.support.ApiTestClient.Companion.DEFAULT_PASSWORD
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.IntegrationTestBase
import com.qrorder.support.LoggedInUser
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Quản lý user (FR-USER-01, FR-USER-02, FR-USER-04) và kịch bản biên SRS §12.4 #1, #2, #3, #5, #6
 * (#4, #7 ở PermissionManagementIT). HTTP thật + PostgreSQL thật, COMMIT thật.
 */
@AutoConfigureMockMvc
class UserManagementIT : IntegrationTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var passwordEncoder: PasswordEncoder
    @Autowired lateinit var userService: UserService

    private val api by lazy { ApiTestClient(mvc, objectMapper, jdbc, passwordEncoder) }
    private lateinit var admin: LoggedInUser

    @BeforeEach
    fun setUp() {
        admin = api.loggedIn("admin1", UserRole.ADMIN)
    }

    private fun roleOf(id: Long): String = jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String::class.java, id)!!
    private fun statusOf(id: Long): String = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, id)!!
    private fun activeAdmins(): Long = fx.count("SELECT count(*) FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE'")

    private fun newUserBody(username: String, role: String, password: String = DEFAULT_PASSWORD) =
        mutableMapOf<String, Any?>("username" to username, "fullName" to "Nguyễn Văn $username", "role" to role, "password" to password)

    // ------------------------------------------------------------------ CRUD

    @Test
    fun `ADMIN creates a STAFF account - 201, created_by, audit USER_CREATED with correlation id and IP, new user can log in`() {
        val result = api.perform(
            MockMvcRequestBuilders.post("/api/v1/users"),
            admin.accessToken, newUserBody("phuc_vu_1", "STAFF"), correlationId = "tao-user-001",
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.username").value("phuc_vu_1"))
            .andExpect(jsonPath("$.data.role").value("STAFF"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.version").value(0))
        val id = api.data(result)["id"].asLong()
        assertTrue(!api.json(result).toString().contains("passwordHash"))

        assertEquals(admin.id, fx.count("SELECT created_by FROM users WHERE id = ?", id))
        assertTrue(
            jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String::class.java, id)!!.startsWith("{bcrypt}"),
        )
        val audit = api.auditRows("USER_CREATED").single()
        assertEquals("USER", audit["actor_type"])
        assertEquals(admin.id, (audit["actor_user_id"] as Number).toLong())
        assertEquals("users", audit["entity_type"])
        assertEquals(id, (audit["entity_id"] as Number).toLong())
        assertEquals("STAFF", api.jsonOf(audit["new_value"])["role"].asText())
        assertTrue(!(audit["new_value"] as String).contains("password", ignoreCase = true))
        assertEquals("tao-user-001", audit["correlation_id"])
        assertEquals("127.0.0.1", audit["ip_address"])
        assertEquals(false, audit["reauthenticated"])

        api.login("phuc_vu_1", DEFAULT_PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `create validates password policy, username format and uniqueness (case-insensitive)`() {
        api.post("/api/v1/users", admin.accessToken, newUserBody("yeu_1", "STAFF", password = "abc12345"))
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[*].field", hasItem("password")))
        api.post("/api/v1/users", admin.accessToken, newUserBody("có dấu", "STAFF"))
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[*].field", hasItem("username")))
        api.post("/api/v1/users", admin.accessToken, newUserBody("bep_1", "KITCHEN")).andExpect(status().isCreated)
        api.post("/api/v1/users", admin.accessToken, newUserBody("BEP_1", "STAFF")).isError(409, "USERNAME_TAKEN")
        api.postRaw("/api/v1/users", admin.accessToken, """{"username":"x_1","fullName":"X","role":"SUPER","password":"Matkhau123"}""")
            .isError(400, "MALFORMED_REQUEST")
    }

    @Test
    fun `only ADMIN can manage users (MANAGER, STAFF get 403)`() {
        val manager = api.loggedIn("quan_ly", UserRole.MANAGER)
        val staff = api.loggedIn("nhan_vien", UserRole.STAFF)
        listOf(manager, staff).forEach {
            api.get("/api/v1/users", it.accessToken).isError(403, "PERMISSION_DENIED")
            api.post("/api/v1/users", it.accessToken, newUserBody("moi_${it.username}", "STAFF")).isError(403, "PERMISSION_DENIED")
        }
        api.get("/api/v1/users", null).isError(401, "UNAUTHENTICATED")
    }

    @Test
    fun `list filters by role, status and keyword with page metadata and effective permissions`() {
        api.createUser("bep_a", UserRole.KITCHEN)
        api.createUser("bep_b", UserRole.KITCHEN, status = "LOCKED")
        api.createUser("thu_ngan_a", UserRole.STAFF)
        api.createUser("quan_ly_a", UserRole.MANAGER)

        api.get("/api/v1/users?role=KITCHEN", admin.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(2))
        api.get("/api/v1/users?role=KITCHEN&status=ACTIVE", admin.accessToken)
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].username").value("bep_a"))
        api.get("/api/v1/users?q=THU_", admin.accessToken)
            .andExpect(jsonPath("$.data.totalElements").value(1))
        api.get("/api/v1/users?q=%25", admin.accessToken) // ký tự % được hiểu theo nghĩa đen
            .andExpect(jsonPath("$.data.totalElements").value(0))
        api.get("/api/v1/users?q=quan_ly_a", admin.accessToken)
            .andExpect(jsonPath("$.data.items[0].permissions.length()").value(8))
        api.get("/api/v1/users?page=0&size=2", admin.accessToken)
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.size").value(2))
            .andExpect(jsonPath("$.data.totalElements").value(5))
            .andExpect(jsonPath("$.data.totalPages").value(3))
        api.get("/api/v1/users/999999", admin.accessToken).isError(404, "RESOURCE_NOT_FOUND")
    }

    @Test
    fun `update full name uses optimistic locking via version`() {
        val id = api.createUser("doi_ten", UserRole.STAFF)
        api.put("/api/v1/users/$id", admin.accessToken, mapOf("fullName" to "  Trần Thị B  ", "version" to 0))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.fullName").value("Trần Thị B"))
            .andExpect(jsonPath("$.data.version").value(1))
        api.put("/api/v1/users/$id", admin.accessToken, mapOf("fullName" to "Tên khác", "version" to 0))
            .isError(409, "CONCURRENT_MODIFICATION")
        assertEquals("Trần Thị B", jdbc.queryForObject("SELECT full_name FROM users WHERE id = ?", String::class.java, id))
        assertEquals(1, api.auditRows("USER_UPDATED").size)
    }

    // ------------------------------------------------------------------ lock / unlock / disable

    @Test
    fun `lock takes effect immediately - access token rejected, refresh tokens revoked by trigger, audit USER_LOCKED, unlock restores`() {
        val staff = api.loggedIn("se_bi_khoa", UserRole.STAFF)

        api.patch("/api/v1/users/${staff.id}/status", admin.accessToken, mapOf("status" to "LOCKED", "reason" to "Nghỉ phép dài"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("LOCKED"))

        api.get("/api/v1/auth/me", staff.accessToken).isError(401, "ACCOUNT_DISABLED")
        assertEquals(listOf("USER_LOCKED"), api.revokeReasons(staff.id))
        api.login(staff.username, DEFAULT_PASSWORD).isError(401, "ACCOUNT_DISABLED")
        val locked = api.auditRows("USER_LOCKED").single()
        assertEquals("ACTIVE", api.jsonOf(locked["old_value"])["status"].asText())
        assertEquals("LOCKED", api.jsonOf(locked["new_value"])["status"].asText())
        assertEquals("Nghỉ phép dài", locked["reason"])

        api.patch("/api/v1/users/${staff.id}/status", admin.accessToken, mapOf("status" to "LOCKED"))
            .isError(422, "BUSINESS_RULE_VIOLATION")
        api.patch("/api/v1/users/${staff.id}/status", admin.accessToken, mapOf("status" to "ACTIVE")).andExpect(status().isOk)
        assertEquals(1, api.auditRows("USER_UNLOCKED").size)
        api.login(staff.username, DEFAULT_PASSWORD).andExpect(status().isOk)

        api.patch("/api/v1/users/${staff.id}/status", admin.accessToken, mapOf("status" to "DISABLED")).andExpect(status().isOk)
        assertEquals(1, api.auditRows("USER_DISABLED").size)
        assertEquals("DISABLED", statusOf(staff.id))
    }

    @Test
    fun `SRS 12_4 #5 - the last active ADMIN cannot be locked, disabled or demoted, even by itself`() {
        val base = "/api/v1/users/${admin.id}"
        api.patch("$base/status", admin.accessToken, mapOf("status" to "LOCKED")).isError(409, "LAST_ACTIVE_ADMIN")
        api.patch("$base/status", admin.accessToken, mapOf("status" to "DISABLED")).isError(409, "LAST_ACTIVE_ADMIN")
        api.patch(
            "$base/role", admin.accessToken,
            mapOf("role" to "MANAGER", "reason" to "Tự hạ quyền", "currentPassword" to DEFAULT_PASSWORD),
        ).isError(409, "LAST_ACTIVE_ADMIN")
        assertEquals("ADMIN", roleOf(admin.id))
        assertEquals("ACTIVE", statusOf(admin.id))

        // Có ADMIN thứ hai đang hoạt động thì được phép.
        val second = api.createUser("admin2", UserRole.ADMIN)
        api.patch("/api/v1/users/$second/status", admin.accessToken, mapOf("status" to "LOCKED")).andExpect(status().isOk)
        api.patch("$base/status", admin.accessToken, mapOf("status" to "LOCKED")).isError(409, "LAST_ACTIVE_ADMIN")
        assertEquals(1L, activeAdmins())
    }

    // ------------------------------------------------------------------ reset password

    @Test
    fun `ADMIN resets another user's password - refresh tokens revoked (PASSWORD_CHANGED), audit, not allowed on self`() {
        val staff = api.loggedIn("quen_mk", UserRole.STAFF)
        api.post("/api/v1/users/${staff.id}/reset-password", admin.accessToken, mapOf("newPassword" to "yeu"))
            .isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/users/${staff.id}/reset-password", admin.accessToken, mapOf("newPassword" to "MatKhauMoi9", "reason" to "Quên mật khẩu"))
            .andExpect(status().isOk)

        assertEquals(listOf("PASSWORD_CHANGED"), api.revokeReasons(staff.id))
        api.login(staff.username, DEFAULT_PASSWORD).isError(401, "INVALID_CREDENTIALS")
        api.login(staff.username, "MatKhauMoi9").andExpect(status().isOk)
        val audit = api.auditRows("USER_PASSWORD_RESET").single()
        assertEquals("Quên mật khẩu", audit["reason"])
        assertEquals(null, audit["new_value"])

        api.post("/api/v1/users/${admin.id}/reset-password", admin.accessToken, mapOf("newPassword" to "MatKhauMoi9"))
            .isError(422, "BUSINESS_RULE_VIOLATION")
    }

    // ------------------------------------------------------------------ role change (BR-A08, BR-A09)

    @Test
    fun `SRS 12_4 #1 - ADMIN promotes a user to ADMIN with re-authentication and reason, full audit, target tokens revoked`() {
        val manager = api.loggedIn("len_admin", UserRole.MANAGER)

        api.perform(
            MockMvcRequestBuilders.patch("/api/v1/users/${manager.id}/role"),
            admin.accessToken,
            mapOf("role" to "ADMIN", "reason" to "Bổ nhiệm quản trị hệ thống", "currentPassword" to DEFAULT_PASSWORD),
            correlationId = "doi-role-001",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.role").value("ADMIN"))
            .andExpect(jsonPath("$.data.permissions.length()").value(0)) // ADMIN không kế thừa quyền MANAGER

        assertEquals("ADMIN", roleOf(manager.id))
        val audit = api.auditRows("USER_ROLE_CHANGED").single()
        assertEquals("USER", audit["actor_type"])
        assertEquals(admin.id, (audit["actor_user_id"] as Number).toLong()) // actor
        assertEquals("users", audit["entity_type"])
        assertEquals(manager.id, (audit["entity_id"] as Number).toLong()) // người bị đổi
        assertEquals("MANAGER", api.jsonOf(audit["old_value"])["role"].asText()) // role cũ
        assertEquals("ADMIN", api.jsonOf(audit["new_value"])["role"].asText()) // role mới
        assertEquals("Bổ nhiệm quản trị hệ thống", audit["reason"]) // lý do
        assertEquals(true, audit["reauthenticated"])
        assertNotNull(audit["occurred_at"]) // thời điểm
        assertEquals("doi-role-001", audit["correlation_id"])

        // BR-A04: role đổi có hiệu lực ngay.
        assertEquals(listOf("ROLE_CHANGED"), api.revokeReasons(manager.id))
        api.get("/api/v1/auth/me", manager.accessToken).isError(401, "TOKEN_INVALID")
        val relogin = api.loginData(manager.username, DEFAULT_PASSWORD)
        assertEquals("ADMIN", relogin["user"]["role"].asText())
    }

    @Test
    fun `SRS 12_4 #2 - role change without re-authentication or without reason is rejected and nothing changes`() {
        val staff = api.createUser("chua_doi", UserRole.STAFF)
        val path = "/api/v1/users/$staff/role"

        api.patch(path, admin.accessToken, mapOf("role" to "ADMIN", "reason" to "Bổ nhiệm"))
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[*].field", hasItem("currentPassword")))
        api.patch(path, admin.accessToken, mapOf("role" to "ADMIN", "currentPassword" to DEFAULT_PASSWORD))
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[*].field", hasItem("reason")))
        api.patch(path, admin.accessToken, mapOf("role" to "ADMIN", "reason" to "   ", "currentPassword" to DEFAULT_PASSWORD))
            .isError(400, "VALIDATION_FAILED")
        api.patch(path, admin.accessToken, mapOf("role" to "ADMIN", "reason" to "Bổ nhiệm", "currentPassword" to "SaiMatKhau1"))
            .isError(403, "REAUTHENTICATION_FAILED")

        assertEquals("STAFF", roleOf(staff))
        assertTrue(api.auditRows("USER_ROLE_CHANGED").isEmpty())

        // Tạo trực tiếp tài khoản ADMIN cũng là cấp role ADMIN: cần xác thực lại + lý do.
        api.post("/api/v1/users", admin.accessToken, newUserBody("admin_moi", "ADMIN")).isError(400, "VALIDATION_FAILED")
        api.post(
            "/api/v1/users", admin.accessToken,
            newUserBody("admin_moi", "ADMIN").apply { put("reason", "Thêm quản trị"); put("currentPassword", "SaiMatKhau1") },
        ).isError(403, "REAUTHENTICATION_FAILED")
        api.post(
            "/api/v1/users", admin.accessToken,
            newUserBody("admin_moi", "ADMIN").apply { put("reason", "Thêm quản trị"); put("currentPassword", DEFAULT_PASSWORD) },
        ).andExpect(status().isCreated)
        val created = api.auditRows("USER_CREATED").single()
        assertEquals(true, created["reauthenticated"])
        assertEquals("Thêm quản trị", created["reason"])
    }

    @Test
    fun `SRS 12_4 #3 - STAFF or MANAGER cannot grant themselves the ADMIN role`() {
        val staff = api.loggedIn("muon_len", UserRole.STAFF)
        val manager = api.loggedIn("quan_ly_tham", UserRole.MANAGER)
        listOf(staff, manager).forEach {
            api.patch(
                "/api/v1/users/${it.id}/role", it.accessToken,
                mapOf("role" to "ADMIN", "reason" to "Tự cấp", "currentPassword" to DEFAULT_PASSWORD),
            ).isError(403, "PERMISSION_DENIED")
            api.post(
                "/api/v1/users", it.accessToken,
                newUserBody("phu_${it.username}", "ADMIN").apply { put("reason", "x"); put("currentPassword", DEFAULT_PASSWORD) },
            ).isError(403, "PERMISSION_DENIED")
        }
        assertEquals("STAFF", roleOf(staff.id))
        assertEquals("MANAGER", roleOf(manager.id))
        assertEquals(1L, activeAdmins())
    }

    @Test
    fun `role change to the same role is rejected, demoting one of two admins is allowed`() {
        val second = api.createUser("admin2", UserRole.ADMIN)
        val path = "/api/v1/users/$second/role"
        api.patch(path, admin.accessToken, mapOf("role" to "ADMIN", "reason" to "x", "currentPassword" to DEFAULT_PASSWORD))
            .isError(422, "BUSINESS_RULE_VIOLATION")
        api.patch(path, admin.accessToken, mapOf("role" to "STAFF", "reason" to "Chuyển bộ phận", "currentPassword" to DEFAULT_PASSWORD))
            .andExpect(status().isOk)
        assertEquals("STAFF", roleOf(second))
        assertEquals(1L, activeAdmins())
    }

    @Test
    fun `SRS 12_4 #6 - two ADMINs demote each other at the same time - only one succeeds, one ADMIN remains`() {
        val second = api.createUser("admin2", UserRole.ADMIN)
        repeat(3) { round ->
            // Mỗi vòng đặt lại: hai ADMIN đang hoạt động.
            jdbc.update("UPDATE users SET role = 'ADMIN', status = 'ACTIVE' WHERE id IN (?, ?)", admin.id, second)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                fun demote(actor: Long, target: Long) = Callable {
                    start.await()
                    runCatching {
                        userService.changeRole(
                            actor, target,
                            ChangeUserRoleRequest(UserRole.MANAGER, "Hạ quyền vòng $round", DEFAULT_PASSWORD),
                        )
                    }
                }
                val futures = listOf(pool.submit(demote(admin.id, second)), pool.submit(demote(second, admin.id)))
                start.countDown()
                val results = futures.map { it.get(30, TimeUnit.SECONDS) }

                assertEquals(1, results.count { it.isSuccess }, "Vòng $round: đúng một thao tác thành công")
                val failure = results.single { it.isFailure }.exceptionOrNull()!!
                val code = (failure as? BusinessException)?.errorCode ?: dbErrorTranslator.translate(failure)?.errorCode
                assertEquals(ErrorCode.LAST_ACTIVE_ADMIN, code, "Vòng $round: thao tác còn lại bị chặn bởi BR-A09")
                assertEquals(1L, activeAdmins(), "Vòng $round: luôn còn đúng một ADMIN hoạt động")
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
