package com.qrorder.table

import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.TableIntegrationTestBase
import com.qrorder.table.service.SessionExpiryService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BR-S07, DEC-05 A, UC36, FR-SES-06: Scheduler quét phiên idle. Profile test tắt scheduling nên test gọi trực tiếp
 * [SessionExpiryService.runOnce] (cùng code mà @Scheduled gọi mỗi app.session.expiry-check-interval-ms).
 */
class SessionExpiryIT : TableIntegrationTestBase() {

    @Autowired private lateinit var expiry: SessionExpiryService

    @Test
    fun `idle session without unpaid order - EXPIRED, table AVAILABLE, audit SYSTEM, guest token rejected`() {
        val joined = joinOk("dev-qr-T01")
        val sessionId = joined["session"]["sessionId"].asLong()
        makeIdle(sessionId, 181)
        val result = expiry.runOnce()
        assertEquals(listOf(sessionId), result.expired)
        assertEquals("EXPIRED", sessionStatus(sessionId))
        assertNotNull(jdbc.queryForObject("SELECT closed_at FROM table_session WHERE id = ?", java.time.OffsetDateTime::class.java, sessionId))
        assertEquals("AVAILABLE", tableStatus(tableId("T01")), "§9.7 dòng 18")
        val audit = api.auditRows("SESSION_EXPIRED").single()
        assertEquals("SYSTEM", audit["actor_type"])
        assertTrue(stomp.on("/topic/session/$sessionId").any { it["change"].asText() == "CLOSED" && it["status"].asText() == "EXPIRED" })
        api.get("/api/v1/guest/session", joined["accessToken"].asText()).isError(401, "SESSION_CLOSED")
        // Bàn nhận khách mới ngay (phiên mới là dòng mới).
        val again = joinOk("dev-qr-T01")
        assertTrue(again["newSession"].asBoolean())
    }

    @Test
    fun `idle session with unpaid order - stays OPEN, needs_attention once, staff channel notified, no repeat`() {
        val sessionId = joinOk("dev-qr-T02")["session"]["sessionId"].asLong()
        addUnpaidOrder(sessionId)
        makeIdle(sessionId, 200)
        assertEquals(listOf(sessionId), expiry.runOnce().flagged)
        val row = jdbc.queryForMap("SELECT status, needs_attention, attention_reason, attention_set_at FROM table_session WHERE id = ?", sessionId)
        assertEquals("OPEN", row["status"])
        assertEquals(true, row["needs_attention"])
        assertEquals("IDLE_WITH_UNPAID_ORDER", row["attention_reason"])
        assertNotNull(row["attention_set_at"])
        assertEquals("OCCUPIED", tableStatus(tableId("T02")))
        assertEquals(1, api.auditRows("SESSION_NEEDS_ATTENTION").size)
        val staffEvents = stomp.on("/topic/staff/orders")
        assertEquals(1, staffEvents.size)
        assertEquals("SESSION_NEEDS_ATTENTION", staffEvents.single()["type"].asText())
        assertTrue(stomp.on("/topic/session/$sessionId").none { it["change"].asText() == "NEEDS_ATTENTION" }, "Khách không nhận cờ cần xử lý")

        val second = expiry.runOnce()
        assertTrue(second.flagged.isEmpty() && second.expired.isEmpty())
        assertEquals(1, api.auditRows("SESSION_NEEDS_ATTENTION").size, "Không báo lặp")
        assertEquals(1, stomp.on("/topic/staff/orders").size)

        val list = data(api.get("/api/v1/sessions/needs-attention", staff.accessToken).andExpect(status().isOk))
        assertEquals(listOf(sessionId), list.map { it["id"].asLong() })
    }

    @Test
    fun `flagged session expires once its order is settled - flag cleared, empty order VOIDED`() {
        val sessionId = joinOk("dev-qr-T03")["session"]["sessionId"].asLong()
        val (orderId, itemId) = addUnpaidOrder(sessionId)
        makeIdle(sessionId, 240)
        expiry.runOnce()
        cancelItem(itemId)
        assertEquals(listOf(sessionId), expiry.runOnce().expired)
        val row = jdbc.queryForMap("SELECT status, needs_attention, attention_reason FROM table_session WHERE id = ?", sessionId)
        assertEquals("EXPIRED", row["status"])
        assertEquals(false, row["needs_attention"])
        assertEquals("VOIDED", jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String::class.java, orderId))
    }

    @Test
    fun `BILLING sessions never expire, recent activity is skipped, timeout comes from restaurant_settings`() {
        val billing = joinOk("dev-qr-T04")["session"]["sessionId"].asLong()
        jdbc.update("UPDATE table_session SET status = 'BILLING' WHERE id = ?", billing)
        makeIdle(billing, 10_000)
        val fresh = joinOk("dev-qr-T05")["session"]["sessionId"].asLong()
        makeIdle(fresh, 179)
        val result = expiry.runOnce()
        assertTrue(result.expired.isEmpty() && result.flagged.isEmpty(), result.toString())
        assertEquals("BILLING", sessionStatus(billing))
        assertEquals("OPEN", sessionStatus(fresh))

        jdbc.update("UPDATE restaurant_settings SET session_idle_timeout_minutes = 30")
        assertEquals(listOf(fresh), expiry.runOnce().expired)
    }

    @Test
    fun `scan processes every idle session independently`() {
        val ids = listOf("T06", "T07", "T08").map { joinOk("dev-qr-$it")["session"]["sessionId"].asLong() }
        addUnpaidOrder(ids[1])
        ids.forEach { makeIdle(it, 500) }
        val result = expiry.runOnce()
        assertEquals(3, result.scanned)
        assertEquals(setOf(ids[0], ids[2]), result.expired.toSet())
        assertEquals(listOf(ids[1]), result.flagged)
        assertEquals(0, result.failed)
    }
}
