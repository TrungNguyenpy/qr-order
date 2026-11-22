package com.qrorder.billing

import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.BillingIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Đóng phiên tự động (quy tắc chốt GĐ5a, SRS §6.8, §9.7): Bill PAID và không còn món tính tiền chưa xong ->
 * phiên CLOSED, bàn CLEANING (hoặc AVAILABLE); Bill PAID mà món còn đang nấu/chờ mang ra -> giữ BILLING tới món cuối.
 */
class SessionSettlementIT : BillingIntegrationTestBase() {

    @Test
    fun `bill paid after every item is served closes the session at once - table CLEANING, guest JWT rejected`() {
        val (guest, orderId, items) = tableWithStandardOrder()
        items.forEach { advance(it, "SERVED") }
        api.post("/api/v1/guest/service-calls", guest.token, mapOf("type" to "CALL_STAFF")).andExpect(status().isCreated)
        val billId = createBillOk(orderId)["id"].asLong()

        val result = payOk(billId, 159_000)
        assertEquals(true, result["sessionClosed"].asBoolean())
        assertEquals("CLOSED", result["sessionStatus"].asText())

        val session = jdbc.queryForMap("SELECT status, closed_at, closed_by, close_reason FROM table_session WHERE id = ?", guest.sessionId)
        assertEquals("CLOSED", session["status"])
        assertNotNull(session["closed_at"])
        assertEquals(cashier.id, (session["closed_by"] as Number).toLong())
        assertEquals("CLEANING", tableStatus(tableId("T01")))
        assertEquals("PAID", orderStatusOf(guest.sessionId))
        assertEquals("ACTIVE", billRow(billId)["status"], "Bill PAID vẫn ACTIVE (status), payment_status PAID")
        assertEquals("RESOLVED", jdbc.queryForObject("SELECT status FROM service_call WHERE session_id = ?", String::class.java, guest.sessionId))
        assertEquals(1, api.auditRows("SESSION_CLOSED").size)
        assertTrue(stomp.on("/topic/session/${guest.sessionId}").any { it["type"].asText() == "SESSION_CHANGED" && it["change"].asText() == "CLOSED" })
        assertTrue(stomp.on("/topic/tables").any { it["status"].asText() == "CLEANING" })

        // BE-05: phiên CLOSED thì Guest JWT bị từ chối ngay.
        api.get("/api/v1/guest/orders", guest.token).isError(401, "SESSION_CLOSED")
    }

    @Test
    fun `bill paid while food is still cooking keeps the session BILLING until the last item is SERVED`() {
        val (guest, orderId, items) = tableWithStandardOrder()
        val (pho, coffeeItem) = items
        advance(pho, "SERVED")
        advance(coffeeItem, "PREPARING")
        val billId = createBillOk(orderId)["id"].asLong()
        val paid = payOk(billId, 159_000)
        assertEquals(false, paid["sessionClosed"].asBoolean())
        assertEquals("PAID", orderStatusOf(guest.sessionId))
        assertEquals("BILLING", sessionStatus(guest.sessionId))

        // Order PAID vẫn cho bếp/phục vụ đi tiếp (BR-O12 chỉ chặn hủy/từ chối).
        advance(coffeeItem, "READY")
        assertEquals("BILLING", sessionStatus(guest.sessionId), "món READY chưa mang ra")

        api.post("/api/v1/orders/items/$coffeeItem/serve", staff.accessToken, null).andExpect(status().isOk)
        assertEquals("CLOSED", sessionStatus(guest.sessionId), "món cuối cùng SERVED -> tự đóng phiên")
        assertEquals("CLEANING", tableStatus(tableId("T01")))
        val closedBy = jdbc.queryForObject("SELECT closed_by FROM table_session WHERE id = ?", Long::class.java, guest.sessionId)
        assertEquals(staff.id, closedBy, "người phục vụ món cuối được ghi là người đóng phiên")
    }

    @Test
    fun `cleaning_auto_release frees the table right away`() {
        jdbc.update("UPDATE restaurant_settings SET cleaning_auto_release = true")
        val (_, orderId, items) = tableWithStandardOrder()
        items.forEach { advance(it, "SERVED") }
        val billId = createBillOk(orderId)["id"].asLong()
        payOk(billId, 159_000)
        assertEquals("AVAILABLE", tableStatus(tableId("T01")))
        // Bàn trống: khách mới quét QR mở phiên mới.
        val next = guestAt("T01")
        assertEquals("OPEN", sessionStatus(next.sessionId))
    }

    @Test
    fun `a zero-total bill of free items is PAID at creation and closes a fully served session`() {
        val guest = guestAt("T03")
        val items = orderConfirmed(guest, line("Trà đá", 2))
        items.forEach { advance(it, "SERVED") }
        val bill = createBillOk(orderIdOf(guest.sessionId))
        assertEquals(0L, bill["totalAmount"].asLong())
        assertEquals("PAID", bill["paymentStatus"].asText())
        assertEquals("PAID", orderStatusOf(guest.sessionId))
        assertEquals("CLOSED", sessionStatus(guest.sessionId))
        assertEquals(0L, count("SELECT count(*) FROM payment_transaction"))
    }

    @Test
    fun `partial payment never closes the session`() {
        val (guest, orderId, items) = tableWithStandardOrder()
        items.forEach { advance(it, "SERVED") }
        val billId = createBillOk(orderId)["id"].asLong()
        payOk(billId, 100_000)
        assertEquals("BILLING", sessionStatus(guest.sessionId))
        assertEquals("LOCKED", orderStatusOf(guest.sessionId))
        assertEquals("PAYMENT_PENDING", tableStatus(tableId("T01")))
    }
}
