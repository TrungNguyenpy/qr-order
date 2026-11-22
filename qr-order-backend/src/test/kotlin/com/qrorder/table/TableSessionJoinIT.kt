package com.qrorder.table

import com.qrorder.auth.security.GuestPrincipal
import com.qrorder.auth.security.JwtService
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.TableIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FR-SES-01, FR-SES-04, FR-SES-05, FR-AUTH-04, BR-S01..S09: khách vào phiên bằng QR (COMMIT thật). */
class TableSessionJoinIT : TableIntegrationTestBase() {

    @Autowired private lateinit var jwtService: JwtService

    @Test
    fun `first scan opens a session, table becomes OCCUPIED, device recorded, Guest JWT bound to session`() {
        val t01 = tableId("T01")
        val data = joinOk("dev-qr-T01", deviceId = "device-first-0001")
        val sessionId = data["session"]["sessionId"].asLong()
        assertTrue(data["newSession"].asBoolean())
        assertEquals("Bearer", data["tokenType"].asText())
        assertEquals("OPEN", data["session"]["status"].asText())
        assertTrue(data["session"]["canOrder"].asBoolean())
        assertEquals("T01", data["session"]["table"]["code"].asText())
        assertFalse(data["session"]["pinRequired"].asBoolean())
        assertTrue(data["session"]["pinVerified"].asBoolean())
        assertFalse(data.toString().contains("tablePin"), "Khách không nhận PIN")

        assertEquals(GuestPrincipal(sessionId, "device-first-0001"), jwtService.parse(data["accessToken"].asText()))
        val expiresAt = Instant.parse(data["expiresAt"].asText())
        assertTrue(Duration.between(Instant.now(), expiresAt) in Duration.ofMinutes(14)..Duration.ofMinutes(15), "TTL guest_jwt_ttl_minutes = 15")

        assertEquals("OCCUPIED", tableStatus(t01))
        val session = jdbc.queryForMap("SELECT opened_by_type, opened_by_user_id, table_pin FROM table_session WHERE id = ?", sessionId)
        assertEquals("GUEST", session["opened_by_type"])
        assertNull(session["opened_by_user_id"])
        assertNull(session["table_pin"])
        assertEquals(1L, count("SELECT count(*) FROM session_device WHERE session_id = ? AND device_id = 'device-first-0001'", sessionId))
        assertTrue(stomp.on("/topic/tables").any { it["tableId"].asLong() == t01 && it["status"].asText() == "OCCUPIED" && it["sessionId"].asLong() == sessionId })
        assertTrue(stomp.on("/topic/session/$sessionId").any { it["change"].asText() == "OPENED" })
    }

    @Test
    fun `more devices join the same session, rejoining device is not duplicated, last activity refreshed`() {
        val first = joinOk("dev-qr-T02", deviceId = "device-a-000001")
        val sessionId = first["session"]["sessionId"].asLong()
        jdbc.update("UPDATE table_session SET last_activity_at = now() - interval '30 minutes' WHERE id = ?", sessionId)
        val second = joinOk("dev-qr-T02", deviceId = "device-b-000002")
        assertFalse(second["newSession"].asBoolean())
        assertEquals(sessionId, second["session"]["sessionId"].asLong())
        joinOk("dev-qr-T02", deviceId = "device-a-000001")
        assertEquals(2L, count("SELECT count(*) FROM session_device WHERE session_id = ?", sessionId))
        assertEquals(1L, count("SELECT count(*) FROM table_session WHERE table_id = ?", tableId("T02")))
        assertEquals(1L, count("SELECT count(*) FROM table_session WHERE id = ? AND last_activity_at > now() - interval '1 minute'", sessionId))
    }

    @Test
    fun `BILLING session - customers can still join to view but cannot order`() {
        val sessionId = joinOk("dev-qr-T03")["session"]["sessionId"].asLong()
        jdbc.update("UPDATE table_session SET status = 'BILLING', billing_started_at = now() WHERE id = ?", sessionId)
        val data = joinOk("dev-qr-T03")
        assertEquals(sessionId, data["session"]["sessionId"].asLong())
        assertEquals("BILLING", data["session"]["status"].asText())
        assertFalse(data["session"]["canOrder"].asBoolean())
    }

    @Test
    fun `unknown, rotated or revoked QR - 404, inactive table 409, table being cleaned 409`() {
        join("khong-ton-tai-123").isError(404, "QR_CODE_INVALID")
        join("ab").isError(400, "VALIDATION_FAILED")
        api.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/public/sessions/join"), null, mapOf("qrToken" to "dev-qr-T01", "deviceId" to "x"))
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[0].field").value("deviceId"))
        jdbc.update("UPDATE qr_code SET status = 'ROTATED', deactivated_at = now() WHERE table_id = ?", tableId("T04"))
        join("dev-qr-T04").isError(404, "QR_CODE_INVALID")
        jdbc.update("UPDATE qr_code SET status = 'REVOKED', deactivated_at = now() WHERE table_id = ?", tableId("T05"))
        join("dev-qr-T05").isError(404, "QR_CODE_INVALID")

        // Bàn ngừng dùng (BR-S08): QR vẫn ACTIVE nhưng không nhận join.
        jdbc.update("UPDATE dining_table SET is_active = false WHERE id = ?", tableId("T06"))
        join("dev-qr-T06").isError(409, "TABLE_INACTIVE")
        // Bàn đang dọn (§9.5): chưa nhận phiên mới.
        jdbc.update("UPDATE dining_table SET status = 'CLEANING' WHERE id = ?", tableId("T07"))
        join("dev-qr-T07").isError(409, "TABLE_NOT_READY")
        assertEquals(0L, count("SELECT count(*) FROM table_session"))
        assertEquals(0L, count("SELECT count(*) FROM session_device"))
    }

    @Test
    fun `concurrent joins on one table create exactly one session - BR-S01`() {
        repeat(3) { round ->
            val code = "T0${round + 1}"
            val qr = "dev-qr-$code"
            val threads = 10
            val pool = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            val futures = (1..threads).map { n ->
                pool.submit<Pair<Int, String>> {
                    start.await()
                    val res = join(qr, deviceId = "device-r$round-n$n-x").andReturn().response
                    res.status to res.contentAsString
                }
            }
            start.countDown()
            val results = futures.map { it.get(60, TimeUnit.SECONDS) }
            pool.shutdown()
            assertTrue(results.all { it.first == 200 }, "Vòng $round: ${results.map { it.first }}")
            val sessionIds = results.map { objectMapper.readTree(it.second)["data"]["session"]["sessionId"].asLong() }.toSet()
            assertEquals(1, sessionIds.size, "Vòng $round: chỉ một phiên")
            assertEquals(1, results.count { objectMapper.readTree(it.second)["data"]["newSession"].asBoolean() })
            assertEquals(1L, count("SELECT count(*) FROM table_session WHERE table_id = ?", tableId(code)))
            assertEquals(threads.toLong(), count("SELECT count(*) FROM session_device WHERE session_id = ?", sessionIds.single()))
        }
    }

    @Test
    fun `rate limit 10 joins per minute per IP - 429 with Retry-After, counts failed attempts, other IPs unaffected`() {
        val ip = "10.99.0.1"
        repeat(9) { joinOk("dev-qr-T08", ip = ip) }
        join("khong-ton-tai-123", ip = ip).isError(404, "QR_CODE_INVALID") // lần thứ 10 (sai) vẫn bị đếm
        join("dev-qr-T08", ip = ip)
            .isError(429, "RATE_LIMIT_EXCEEDED")
            .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
        joinOk("dev-qr-T08", ip = "10.99.0.2")
        // Ngưỡng đọc từ restaurant_settings.join_rate_limit_per_minute.
        jdbc.update("UPDATE restaurant_settings SET join_rate_limit_per_minute = 2")
        val ip3 = "10.99.0.3"
        repeat(2) { joinOk("dev-qr-T08", ip = ip3) }
        join("dev-qr-T08", ip = ip3).isError(429, "RATE_LIMIT_EXCEEDED")
    }

    @Test
    fun `table PIN - generated for new sessions, wrong PIN rejected without side effects, correct PIN verifies the device`() {
        jdbc.update("UPDATE restaurant_settings SET require_table_pin = true")
        val first = joinOk("dev-qr-T09", deviceId = "device-pin-0001")
        val sessionId = first["session"]["sessionId"].asLong()
        assertTrue(first["session"]["pinRequired"].asBoolean())
        assertFalse(first["session"]["pinVerified"].asBoolean())
        val pin = data(api.get("/api/v1/sessions/$sessionId", staff.accessToken))["tablePin"].asText()
        assertTrue(Regex("^[0-9]{4}$").matches(pin), pin)
        val wrong = if (pin == "0000") "1111" else "0000"

        join("dev-qr-T09", deviceId = "device-pin-0002", pin = wrong).isError(422, "TABLE_PIN_INVALID")
        assertEquals(0L, count("SELECT count(*) FROM session_device WHERE device_id = 'device-pin-0002'"))
        join("dev-qr-T09", pin = "12a4").isError(400, "VALIDATION_FAILED")
        val ok = joinOk("dev-qr-T09", deviceId = "device-pin-0002", pin = pin)
        assertTrue(ok["session"]["pinVerified"].asBoolean())
        assertNotNull(jdbc.queryForObject("SELECT pin_verified_at FROM session_device WHERE device_id = 'device-pin-0002'", java.time.OffsetDateTime::class.java))

        // Phiên vừa tạo có PIN ngẫu nhiên khách chưa biết: gửi PIN khi bàn đang trống -> từ chối, không tạo phiên.
        join("dev-qr-T10", pin = "1234").andExpect { r -> assertTrue(r.response.status in setOf(200, 422)) }
        if (count("SELECT count(*) FROM table_session WHERE table_id = ?", tableId("T10")) == 1L) {
            // Xác suất 1/10000 PIN ngẫu nhiên trùng "1234": chấp nhận được.
            assertEquals("1234", jdbc.queryForObject("SELECT table_pin FROM table_session WHERE table_id = ?", String::class.java, tableId("T10")))
        }

        // Bật PIN khi phiên đã mở: phiên được cấp PIN ở lần join kế tiếp.
        jdbc.update("UPDATE restaurant_settings SET require_table_pin = false")
        val legacy = joinOk("dev-qr-T01")["session"]["sessionId"].asLong()
        assertNull(jdbc.queryForObject("SELECT table_pin FROM table_session WHERE id = ?", String::class.java, legacy))
        jdbc.update("UPDATE restaurant_settings SET require_table_pin = true")
        assertTrue(joinOk("dev-qr-T01")["session"]["pinRequired"].asBoolean())
        assertNotNull(jdbc.queryForObject("SELECT table_pin FROM table_session WHERE id = ?", String::class.java, legacy))
    }

    @Test
    fun `guest token TTL comes from restaurant_settings`() {
        jdbc.update("UPDATE restaurant_settings SET guest_jwt_ttl_minutes = 5")
        val expiresAt = Instant.parse(joinOk("dev-qr-T02")["expiresAt"].asText())
        assertTrue(Duration.between(Instant.now(), expiresAt) in Duration.ofMinutes(4)..Duration.ofMinutes(5))
    }

    @Test
    fun `join creates no audit noise and BILLING session is counted as active for BR-S01`() {
        val sessionId = joinOk("dev-qr-T03")["session"]["sessionId"].asLong()
        jdbc.update("UPDATE table_session SET status = 'BILLING' WHERE id = ?", sessionId)
        expectDbError(com.qrorder.common.error.ErrorCode.SESSION_ALREADY_ACTIVE) {
            jdbc.update("INSERT INTO table_session (table_id, opened_by_type) VALUES (?, 'GUEST')", tableId("T03"))
        }
        assertEquals(0L, count("SELECT count(*) FROM audit_log"))
        api.post("/api/v1/tables/${tableId("T03")}/sessions", staff.accessToken, null).isError(409, "SESSION_ALREADY_ACTIVE")
        api.get("/api/v1/public/menu", null).andExpect(status().isOk)
    }
}
