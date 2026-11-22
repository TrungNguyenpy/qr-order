package com.qrorder.order

import com.qrorder.support.OrderIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Force-close (BR-S06, BR-O13, BR-P02/P18): đợt chờ bị từ chối, Order WRITTEN_OFF (thất thu) hoặc VOIDED. */
class OrderForceCloseIT : OrderIntegrationTestBase() {

    private fun forceClose(sessionId: Long) =
        api.post("/api/v1/sessions/$sessionId/force-close", manager.accessToken, mapOf("reason" to "Khách bỏ về"))

    @Test
    fun `pending batches are REJECTED, billable items are written off with shortfall incl service fee`() {
        jdbc.update("UPDATE restaurant_settings SET service_fee_rate = 0.1, service_fee_taxable = false")
        setMode("ALL") // EVERY_BATCH: mọi đợt đều chờ xác nhận
        val guest = guestAt("T01")
        val (_, confirmed) = placeAndConfirm(guest, staff.accessToken) // Phở nhỏ 65.000
        val (pendingBatch, pendingItem) = placePho(guest)
        assertEquals("PENDING", itemStatus(pendingItem))

        forceClose(guest.sessionId).andExpect(status().isOk)

        assertEquals("FORCE_CLOSED", sessionStatus(guest.sessionId))
        assertEquals("REJECTED", itemStatus(pendingItem))
        assertEquals("SESSION_FORCE_CLOSED", history(pendingItem).last()["reason_code"])
        assertEquals("SYSTEM", history(pendingItem).last()["changed_by_type"])
        assertEquals("REJECTED", batchStatus(pendingBatch))
        assertEquals("CONFIRMED", itemStatus(confirmed))
        val order = jdbc.queryForMap("SELECT status, shortfall_amount, written_off_by FROM orders WHERE session_id = ?", guest.sessionId)
        assertEquals("WRITTEN_OFF", order["status"])
        assertEquals(71_500L, (order["shortfall_amount"] as Number).toLong(), "65.000 + phí phục vụ 10% (BR-P02), chưa Bill nên giảm giá 0")
        assertEquals(manager.id, order["written_off_by"])
        assertEquals(1, api.auditRows("ORDER_WRITTEN_OFF").size)
        assertTrue(stomp.on("/topic/session/${guest.sessionId}").any { it["change"].asText() == "ORDER_CLOSED" })
    }

    @Test
    fun `an order with nothing billable is VOIDED and a session without order closes cleanly`() {
        val guest = guestAt("T01")
        val (_, itemId) = placePho(guest)
        forceClose(guest.sessionId).andExpect(status().isOk)
        assertEquals("REJECTED", itemStatus(itemId))
        val order = jdbc.queryForMap("SELECT status, voided_at, shortfall_amount FROM orders WHERE session_id = ?", guest.sessionId)
        assertEquals("VOIDED", order["status"])
        assertNotNull(order["voided_at"])
        assertNull(order["shortfall_amount"])
        assertEquals(1, api.auditRows("ORDER_VOIDED").size)

        val empty = guestAt("T02")
        forceClose(empty.sessionId).andExpect(status().isOk)
        assertEquals("FORCE_CLOSED", sessionStatus(empty.sessionId))
    }

    @Test
    fun `a LOCKED order with served items is written off too, SERVED items stay SERVED`() {
        val guest = guestAt("T01")
        val (_, itemId) = placeAndConfirm(guest, staff.accessToken)
        api.post("/api/v1/kitchen/items/$itemId/status", kitchen.accessToken, mapOf("status" to "PREPARING")).andExpect(status().isOk)
        api.post("/api/v1/kitchen/items/$itemId/status", kitchen.accessToken, mapOf("status" to "READY")).andExpect(status().isOk)
        api.post("/api/v1/orders/items/$itemId/serve", staff.accessToken, null).andExpect(status().isOk)
        jdbc.update("UPDATE orders SET status = 'LOCKED' WHERE session_id = ?", guest.sessionId)
        forceClose(guest.sessionId).andExpect(status().isOk)
        assertEquals("SERVED", itemStatus(itemId))
        assertEquals("WRITTEN_OFF", orderStatusOf(guest.sessionId))
    }

    @Test
    fun `after force-close the guest can no longer order`() {
        val guest = guestAt("T01")
        placePho(guest)
        forceClose(guest.sessionId).andExpect(status().isOk)
        val code = place(guest, phoSmall()).andReturn().response.status
        assertTrue(code in setOf(401, 403, 409), "Phiên đã đóng nên không đặt được: $code")
    }
}
