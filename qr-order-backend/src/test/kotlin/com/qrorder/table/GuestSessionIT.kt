package com.qrorder.table

import com.qrorder.auth.security.AuthPrincipalResolver
import com.qrorder.auth.security.AuthTokenException
import com.qrorder.auth.security.GuestPrincipal
import com.qrorder.auth.security.JwtService
import com.qrorder.common.error.ErrorCode
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.TableIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * BE-05 (FR-AUTH-04, BR-S03): Guest JWT bị từ chối NGAY khi phiên không còn OPEN/BILLING (REST và STOMP CONNECT),
 * không chờ hết TTL. Gia hạn token, xem phiên, nhập PIN.
 */
class GuestSessionIT : TableIntegrationTestBase() {

    @Autowired private lateinit var resolver: AuthPrincipalResolver
    @Autowired private lateinit var jwtService: JwtService

    @Test
    fun `guest can read its session and refresh the token while the session is OPEN or BILLING`() {
        val joined = joinOk("dev-qr-T01", deviceId = "device-guest-0001")
        val token = joined["accessToken"].asText()
        val sessionId = joined["session"]["sessionId"].asLong()
        api.get("/api/v1/guest/session", token)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sessionId").value(sessionId))
            .andExpect(jsonPath("$.data.deviceId").value("device-guest-0001"))
            .andExpect(jsonPath("$.data.table.code").value("T01"))
        val refreshed = data(api.post("/api/v1/guest/session/token", token, null).andExpect(status().isOk))
        assertNotEquals(token, refreshed["accessToken"].asText())
        assertEquals(GuestPrincipal(sessionId, "device-guest-0001"), jwtService.parse(refreshed["accessToken"].asText()))

        jdbc.update("UPDATE table_session SET status = 'BILLING' WHERE id = ?", sessionId)
        api.get("/api/v1/guest/session", token).andExpect(jsonPath("$.data.canOrder").value(false))
        api.post("/api/v1/guest/session/token", token, null).andExpect(status().isOk)

        // Guest không gọi được API nhân viên; nhân viên không gọi được API khách.
        api.get("/api/v1/tables", token).isError(403, "PERMISSION_DENIED")
        api.get("/api/v1/guest/session", staff.accessToken).isError(403, "PERMISSION_DENIED")
        api.get("/api/v1/guest/session", null).isError(401, "UNAUTHENTICATED")
    }

    @Test
    fun `token is rejected immediately once the session is FORCE_CLOSED, EXPIRED or CLOSED`() {
        for ((code, finalStatus) in listOf("T02" to "FORCE_CLOSED", "T03" to "EXPIRED", "T04" to "CLOSED")) {
            val joined = joinOk("dev-qr-$code")
            val token = joined["accessToken"].asText()
            val sessionId = joined["session"]["sessionId"].asLong()
            api.get("/api/v1/guest/session", token).andExpect(status().isOk)
            if (finalStatus == "FORCE_CLOSED") {
                api.post("/api/v1/sessions/$sessionId/force-close", manager.accessToken, mapOf("reason" to "Khách bỏ về")).andExpect(status().isOk)
            } else {
                if (finalStatus == "CLOSED") jdbc.update("UPDATE table_session SET status = 'BILLING' WHERE id = ?", sessionId)
                jdbc.update("UPDATE table_session SET status = ?, closed_at = now() WHERE id = ?", finalStatus, sessionId)
            }
            api.get("/api/v1/guest/session", token).isError(401, "SESSION_CLOSED")
            api.post("/api/v1/guest/session/token", token, null).isError(401, "SESSION_CLOSED")
            // STOMP CONNECT dùng cùng AuthPrincipalResolver.
            val ex = assertFailsWith<AuthTokenException> { resolver.resolve(token) }
            assertEquals(ErrorCode.SESSION_CLOSED, ex.errorCode)
        }
        // Endpoint public vẫn dùng được với token hết hiệu lực (filter không chặn request public).
        api.get("/api/v1/public/menu", jwtService.issueGuestToken(999_999, "device-xxxx-0001", Duration.ofMinutes(5)).token).andExpect(status().isOk)
    }

    @Test
    fun `token for a session that does not exist or a device not in the session is rejected`() {
        val ghost = jwtService.issueGuestToken(999_999, "device-ghost-01", Duration.ofMinutes(5)).token
        api.get("/api/v1/guest/session", ghost).isError(401, "SESSION_CLOSED")
        val sessionId = joinOk("dev-qr-T05", deviceId = "device-real-0001")["session"]["sessionId"].asLong()
        val forged = jwtService.issueGuestToken(sessionId, "device-forged-01", Duration.ofMinutes(5)).token
        api.get("/api/v1/guest/session", forged).isError(401, "TOKEN_INVALID")
    }

    @Test
    fun `verify-pin - wrong 422, correct verifies, attempts are rate limited per session`() {
        jdbc.update("UPDATE restaurant_settings SET require_table_pin = true, join_rate_limit_per_minute = 2")
        val joined = joinOk("dev-qr-T06", deviceId = "device-vp-00001")
        val token = joined["accessToken"].asText()
        val sessionId = joined["session"]["sessionId"].asLong()
        val pin = jdbc.queryForObject("SELECT table_pin FROM table_session WHERE id = ?", String::class.java, sessionId)!!
        val wrong = if (pin == "0000") "1111" else "0000"
        api.post("/api/v1/guest/session/verify-pin", token, mapOf("pin" to wrong)).isError(422, "TABLE_PIN_INVALID")
        api.post("/api/v1/guest/session/verify-pin", token, mapOf("pin" to "12")).isError(400, "VALIDATION_FAILED")
        val ok = data(api.post("/api/v1/guest/session/verify-pin", token, mapOf("pin" to pin)).andExpect(status().isOk))
        assertTrue(ok["pinVerified"].asBoolean())
        api.post("/api/v1/guest/session/verify-pin", token, mapOf("pin" to pin)).isError(429, "RATE_LIMIT_EXCEEDED")
        // Không bật PIN: không cần xác minh.
        jdbc.update("UPDATE restaurant_settings SET require_table_pin = false")
        api.get("/api/v1/guest/session", token).andExpect(jsonPath("$.data.pinRequired").value(false))
    }
}
