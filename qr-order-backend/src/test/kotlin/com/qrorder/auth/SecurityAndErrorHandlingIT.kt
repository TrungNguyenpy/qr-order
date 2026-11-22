package com.qrorder.auth

import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.security.JwtProperties
import com.qrorder.auth.security.JwtService
import com.qrorder.support.IntegrationTestBase
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Duration

/**
 * Spring Security stateless JWT + RBAC + permission động (SRS §10, BR-A01, BR-A04, BR-A06) và
 * định dạng lỗi ApiResponse/GlobalExceptionHandler, kiểm tra qua HTTP thật (MockMvc + PostgreSQL).
 * Endpoint dùng để kiểm tra nằm trong src/test (TestEndpointsController).
 */
@AutoConfigureMockMvc
class SecurityAndErrorHandlingIT : IntegrationTestBase() {

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @Autowired
    lateinit var jwtProperties: JwtProperties

    private fun tokenFor(userId: Long, username: String, role: UserRole) =
        jwtService.issueStaffAccessToken(userId, username, role).token

    private fun user(name: String, role: UserRole): String = tokenFor(fx.user(name, role), name, role)

    private fun call(path: String, token: String?): ResultActions =
        mvc.perform(get(path).apply { if (token != null) header("Authorization", "Bearer $token") })

    private fun ResultActions.isError(httpStatus: Int, code: String): ResultActions =
        andExpect(status().`is`(httpStatus))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(code))
            .andExpect(jsonPath("$.correlationId").exists())

    @Test
    fun `public endpoint works without token and echoes a correlation id`() {
        mvc.perform(get("/api/v1/public/test/ping").header("X-Correlation-Id", "abc-123"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Correlation-Id", "abc-123"))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.pong").value(true))
            .andExpect(jsonPath("$.correlationId").value("abc-123"))
    }

    @Test
    fun `protected endpoint without, with malformed, or with expired token returns 401 with specific code`() {
        call("/api/v1/test/me", null).isError(401, "UNAUTHENTICATED")
        call("/api/v1/test/me", "khong-phai-jwt").isError(401, "TOKEN_INVALID")

        val pastJwt = JwtService(jwtProperties, Clock.offset(Clock.systemUTC(), Duration.ofHours(-2)))
        val expired = pastJwt.issueStaffAccessToken(fx.user("old", UserRole.STAFF), "old", UserRole.STAFF).token
        call("/api/v1/test/me", expired).isError(401, "TOKEN_EXPIRED")
    }

    @Test
    fun `role hierarchy - MANAGER includes STAFF and KITCHEN, ADMIN does not inherit (O3, BR-A06)`() {
        val staff = user("staff", UserRole.STAFF)
        val kitchen = user("kitchen", UserRole.KITCHEN)
        val manager = user("manager", UserRole.MANAGER)
        val admin = user("admin", UserRole.ADMIN)

        call("/api/v1/test/staff", staff).andExpect(status().isOk)
        call("/api/v1/test/kitchen", staff).isError(403, "PERMISSION_DENIED")
        call("/api/v1/test/kitchen", kitchen).andExpect(status().isOk)
        call("/api/v1/test/staff", manager).andExpect(status().isOk)
        call("/api/v1/test/kitchen", manager).andExpect(status().isOk)
        call("/api/v1/test/staff", admin).isError(403, "PERMISSION_DENIED")
        call("/api/v1/test/admin", admin).andExpect(status().isOk)
        call("/api/v1/test/admin", manager).isError(403, "PERMISSION_DENIED")
    }

    @Test
    fun `dynamic permission - grant and revoke take effect immediately without a new token (BR-A04)`() {
        val adminId = fx.user("admin", UserRole.ADMIN)
        val cashierId = fx.user("cashier", UserRole.STAFF)
        val cashier = tokenFor(cashierId, "cashier", UserRole.STAFF)

        call("/api/v1/test/payment", cashier).isError(403, "PERMISSION_DENIED")
        val grant = fx.grant(cashierId, Permission.PAYMENT_PROCESS, adminId)
        call("/api/v1/test/payment", cashier).andExpect(status().isOk)
        fx.revoke(grant, adminId)
        call("/api/v1/test/payment", cashier).isError(403, "PERMISSION_DENIED")
    }

    @Test
    fun `MANAGER has the 8 permissions by default, ADMIN only when granted`() {
        call("/api/v1/test/payment", user("manager", UserRole.MANAGER)).andExpect(status().isOk)

        val adminId = fx.user("admin", UserRole.ADMIN)
        val admin = tokenFor(adminId, "admin", UserRole.ADMIN)
        call("/api/v1/test/payment", admin).isError(403, "PERMISSION_DENIED")
        fx.grant(adminId, Permission.PAYMENT_PROCESS, adminId) // tự cấp: reauthenticated = true (BR-A07)
        call("/api/v1/test/payment", admin).andExpect(status().isOk)
    }

    @Test
    fun `locked account or changed role invalidates existing access token immediately`() {
        val lockedId = fx.user("locked", UserRole.STAFF)
        val lockedToken = tokenFor(lockedId, "locked", UserRole.STAFF)
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", lockedId)
        call("/api/v1/test/me", lockedToken).isError(401, "ACCOUNT_DISABLED")

        val movedId = fx.user("moved", UserRole.STAFF)
        val movedToken = tokenFor(movedId, "moved", UserRole.STAFF)
        jdbc.update("UPDATE users SET role = 'KITCHEN' WHERE id = ?", movedId)
        call("/api/v1/test/me", movedToken).isError(401, "TOKEN_INVALID")
    }

    @Test
    fun `guest token cannot call staff endpoints`() {
        // Giai đoạn 3 (BE-05): Guest JWT phải gắn với phiên OPEN/BILLING có thiết bị tương ứng.
        val sessionId = fx.guestSession("T01", "device-0001")
        val guest = jwtService.issueGuestToken(sessionId = sessionId, deviceId = "device-0001", ttl = Duration.ofMinutes(15)).token
        call("/api/v1/test/me", guest).andExpect(status().isOk).andExpect(jsonPath("$.data.name").value("guest:$sessionId:device-0001"))
        call("/api/v1/test/staff", guest).isError(403, "PERMISSION_DENIED")
    }

    @Test
    fun `bean validation errors return 400 VALIDATION_FAILED with field details`() {
        mvc.perform(
            post("/api/v1/test/validation")
                .header("Authorization", "Bearer ${user("staff", UserRole.STAFF)}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":" ","quantity":0}"""),
        )
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[*].field", hasItem("name")))
            .andExpect(jsonPath("$.error.details[*].field", hasItem("quantity")))
    }

    @Test
    fun `malformed JSON returns 400 MALFORMED_REQUEST`() {
        mvc.perform(
            post("/api/v1/test/validation")
                .header("Authorization", "Bearer ${user("staff", UserRole.STAFF)}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{khong-hop-le"),
        ).isError(400, "MALFORMED_REQUEST")
    }

    @Test
    fun `PostgreSQL trigger error LAST_ACTIVE_ADMIN is mapped to HTTP 409 without leaking the database message`() {
        val adminId = fx.user("only_admin", UserRole.ADMIN)

        mvc.perform(
            post("/api/v1/test/db/lock-user/$adminId")
                .header("Authorization", "Bearer ${tokenFor(adminId, "only_admin", UserRole.ADMIN)}"),
        )
            .isError(409, "LAST_ACTIVE_ADMIN")
            .andExpect(jsonPath("$.error.message").value("Không thể hạ quyền, khóa hoặc ngừng dùng ADMIN hoạt động cuối cùng"))
    }
}
