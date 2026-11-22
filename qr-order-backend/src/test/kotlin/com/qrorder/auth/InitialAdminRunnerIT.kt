package com.qrorder.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.qrorder.auth.bootstrap.InitialAdminProperties
import com.qrorder.auth.bootstrap.InitialAdminRunner
import com.qrorder.auth.bootstrap.InitialAdminRunner.Outcome
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.password.PasswordService
import com.qrorder.auth.repository.UserRepository
import com.qrorder.common.audit.AuditService
import com.qrorder.support.ApiTestClient
import com.qrorder.support.IntegrationTestBase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Khởi tạo ADMIN đầu tiên (BR-A09; quyết định 20/11/2026): chỉ khi users trống, đọc INITIAL_ADMIN_*,
 * thiếu biến chỉ cảnh báo, không bao giờ ghi mật khẩu ra log, không bắt buộc đổi mật khẩu lần đầu.
 * Runner được tạo trực tiếp với cấu hình của từng test (bean của ứng dụng chạy với cấu hình rỗng).
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class InitialAdminRunnerIT : IntegrationTestBase() {

    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var passwordService: PasswordService
    @Autowired lateinit var auditService: AuditService
    @Autowired lateinit var startupRunner: InitialAdminRunner
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val api by lazy { ApiTestClient(mvc, objectMapper, jdbc, passwordEncoder) }

    private fun runner(username: String, password: String, fullName: String) =
        InitialAdminRunner(
            InitialAdminProperties(username, password, fullName),
            userRepository, passwordService, auditService, jdbc, transactionManager,
        )

    private fun userCount(): Long = fx.count("SELECT count(*) FROM users")

    @Test
    fun `creates the first ADMIN when users is empty - BCrypt hash, SYSTEM audit, can log in directly, password never logged`(
        output: CapturedOutput,
    ) {
        val password = "KhoiTao2026Bi"

        assertEquals(Outcome.CREATED, runner("  root_admin ", password, " Quản trị viên ").createIfNoUsers())

        val row = jdbc.queryForMap("SELECT id, username, full_name, role, status, password_hash, created_by FROM users")
        assertEquals("root_admin", row["username"])
        assertEquals("Quản trị viên", row["full_name"])
        assertEquals("ADMIN", row["role"])
        assertEquals("ACTIVE", row["status"])
        assertNull(row["created_by"])
        val hash = row["password_hash"] as String
        assertTrue(hash.startsWith("{bcrypt}"))
        assertFalse(hash.contains(password))

        val audit = api.auditRows("USER_CREATED").single()
        assertEquals("SYSTEM", audit["actor_type"])
        assertNull(audit["actor_user_id"])
        assertEquals("users", audit["entity_type"])
        assertEquals((row["id"] as Number).toLong(), (audit["entity_id"] as Number).toLong())
        assertEquals("ADMIN", api.jsonOf(audit["new_value"])["role"].asText())
        assertFalse((audit["new_value"] as String).contains(password))

        // Không bắt buộc đổi mật khẩu lần đầu: đăng nhập nhận token ngay và dùng được API của ADMIN.
        val token = api.loginData("root_admin", password)["accessToken"].asText()
        api.get("/api/v1/users", token).andExpect(status().isOk).andExpect(jsonPath("$.data.totalElements").value(1))

        assertTrue(output.all.contains("root_admin"))
        assertFalse(output.all.contains(password), "Mật khẩu không được xuất hiện trong log")
    }

    @Test
    fun `does nothing when users already has any record`(output: CapturedOutput) {
        api.createUser("da_co", UserRole.STAFF)

        assertEquals(Outcome.SKIPPED_USERS_EXIST, runner("root_admin", "KhoiTao2026Bi", "Root").createIfNoUsers())

        assertEquals(1L, userCount())
        assertEquals(0L, fx.count("SELECT count(*) FROM users WHERE role = 'ADMIN'"))
        assertFalse(output.all.contains("KhoiTao2026Bi"))
    }

    @Test
    fun `missing environment variables only log a warning naming them and skip`(output: CapturedOutput) {
        assertEquals(Outcome.SKIPPED_MISSING_CONFIG, runner("root_admin", "", "Root").createIfNoUsers())
        assertEquals(Outcome.SKIPPED_MISSING_CONFIG, runner("", "KhoiTao2026Bi", " ").createIfNoUsers())

        assertEquals(0L, userCount())
        assertTrue(output.all.contains("INITIAL_ADMIN_PASSWORD"))
        assertTrue(output.all.contains("INITIAL_ADMIN_USERNAME"))
        assertTrue(output.all.contains("INITIAL_ADMIN_FULL_NAME"))
        assertFalse(output.all.contains("KhoiTao2026Bi"))

        // Bean của ứng dụng (cấu hình test để trống) cũng chỉ cảnh báo.
        assertEquals(Outcome.SKIPPED_MISSING_CONFIG, startupRunner.createIfNoUsers())
        assertEquals(0L, userCount())
    }

    @Test
    fun `invalid configuration is rejected without logging the password`(output: CapturedOutput) {
        val weak = "matkhauyeu"
        assertEquals(Outcome.SKIPPED_INVALID_CONFIG, runner("root_admin", weak, "Root").createIfNoUsers())
        assertEquals(Outcome.SKIPPED_INVALID_CONFIG, runner("ad", "KhoiTao2026Bi", "Root").createIfNoUsers())
        assertEquals(Outcome.SKIPPED_INVALID_CONFIG, runner("quản_trị", "KhoiTao2026Bi", "Root").createIfNoUsers())

        assertEquals(0L, userCount())
        assertFalse(output.all.contains(weak))
        assertFalse(output.all.contains("KhoiTao2026Bi"))
    }

    @Test
    fun `running twice creates only one ADMIN`() {
        val first = runner("root_admin", "KhoiTao2026Bi", "Root")
        assertEquals(Outcome.CREATED, first.createIfNoUsers())
        assertEquals(Outcome.SKIPPED_USERS_EXIST, runner("root_admin2", "KhoiTao2026Bi", "Root 2").createIfNoUsers())
        assertEquals(1L, userCount())
    }
}
