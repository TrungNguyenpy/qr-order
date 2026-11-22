package com.qrorder.billing

import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.BillingIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Force-close có Bill (BE-22, BR-S06, BR-P15, BR-P18, P-1, P3 - SRS §12.2): chặn khi còn Payment PENDING hoặc Bill
 * VOID_PENDING; còn thiếu tiền -> Order + Bill WRITTEN_OFF với thất thu = Total - net_collected, giữ Payment SUCCESS,
 * không Refund; Order đã PAID giữ nguyên.
 */
class BillingForceCloseIT : BillingIntegrationTestBase() {

    private fun forceClose(sessionId: Long, reason: String = "Khách bỏ về") =
        api.post("/api/v1/sessions/$sessionId/force-close", manager.accessToken, mapOf("reason" to reason))

    @Test
    fun `partially paid bill - order and bill WRITTEN_OFF, shortfall is total minus collected, SUCCESS kept`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        payOk(billId, 59_000)

        forceClose(guest.sessionId).andExpect(status().isOk)

        assertEquals("FORCE_CLOSED", sessionStatus(guest.sessionId))
        val order = jdbc.queryForMap("SELECT status, shortfall_amount, writeoff_reason, written_off_by FROM orders WHERE id = ?", orderId)
        assertEquals("WRITTEN_OFF", order["status"])
        assertEquals(100_000L, long(order, "shortfall_amount"), "159000 - 59000 (BR-P15)")
        assertEquals("Khách bỏ về", order["writeoff_reason"])
        assertEquals(manager.id, long(order, "written_off_by"))
        val bill = billRow(billId)
        assertEquals("WRITTEN_OFF", bill["status"])
        assertEquals("PARTIALLY_PAID", bill["payment_status"], "không đánh PAID (BR-P15)")
        assertNotNull(bill["written_off_at"])
        assertEquals(1L, count("SELECT count(*) FROM payment_transaction WHERE bill_id = ? AND status = 'SUCCESS'", billId))
        assertEquals(0L, count("SELECT count(*) FROM refund_transaction"))
        assertEquals(1, api.auditRows("ORDER_WRITTEN_OFF").size)
        assertEquals(1, api.auditRows("BILL_WRITTEN_OFF").size)
        assertTrue(stomp.on("/topic/session/${guest.sessionId}").any { it["type"].asText() == "BILL_CHANGED" && it["change"].asText() == "BILL_WRITTEN_OFF" })

        // WRITTEN_OFF là trạng thái cuối (P-2): không thu thêm.
        pay(billId, 1_000).isError(409, "PAYMENT_BILL_NOT_ACTIVE")
    }

    @Test
    fun `unpaid bill with a discount - shortfall uses the bill total, not the item subtotal`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId, manager.accessToken, mapOf("type" to "FIXED_AMOUNT", "value" to 9_000, "reason" to "Món ra chậm"))["id"].asLong()
        forceClose(guest.sessionId).andExpect(status().isOk)
        assertEquals(150_000L, long(jdbc.queryForMap("SELECT shortfall_amount FROM orders WHERE id = ?", orderId), "shortfall_amount"))
        assertEquals("UNPAID", billRow(billId)["payment_status"])
        assertEquals("WRITTEN_OFF", billRow(billId)["status"])
    }

    @Test
    fun `a PENDING payment blocks force-close and nothing changes`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        val pendingId = payOk(billId, 59_000, "PENDING", "CARD_POS")["payment"]["id"].asLong()

        forceClose(guest.sessionId).isError(409, "PENDING_PAYMENT_EXISTS")
        assertEquals("BILLING", sessionStatus(guest.sessionId))
        assertEquals("LOCKED", orderStatusOf(guest.sessionId))
        assertEquals("ACTIVE", billRow(billId)["status"])
        assertEquals(0, api.auditRows("SESSION_FORCE_CLOSED").size)

        confirmPayment(pendingId, "FAILED", "Khách hủy quẹt thẻ").andExpect(status().isOk)
        forceClose(guest.sessionId).andExpect(status().isOk)
        assertEquals(159_000L, long(jdbc.queryForMap("SELECT shortfall_amount FROM orders WHERE id = ?", orderId), "shortfall_amount"))
    }

    @Test
    fun `a VOID_PENDING bill blocks force-close - BR-S06 (i)`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        payOk(billId, 59_000)
        // GĐ5b mới có API Void; mô phỏng Manager yêu cầu Void bằng fixture SQL.
        fx.requestVoid(billId, orderId, manager.id, "LOCKED")
        forceClose(guest.sessionId).isError(409, "BILL_VOID_PENDING")
        assertEquals("BILLING", sessionStatus(guest.sessionId))
        assertEquals("VOID_PENDING", billRow(billId)["status"])
    }

    @Test
    fun `an order already PAID while food is cooking stays PAID - only the session is FORCE_CLOSED`() {
        val (guest, orderId, items) = tableWithStandardOrder()
        advance(items[0], "PREPARING")
        val billId = createBillOk(orderId)["id"].asLong()
        payOk(billId, 159_000)
        assertEquals("BILLING", sessionStatus(guest.sessionId))

        forceClose(guest.sessionId, "Đóng ca").andExpect(status().isOk)
        assertEquals("FORCE_CLOSED", sessionStatus(guest.sessionId))
        assertEquals("PAID", orderStatusOf(guest.sessionId))
        assertEquals("ACTIVE", billRow(billId)["status"])
        assertEquals("PAID", billRow(billId)["payment_status"])
        assertEquals(0, api.auditRows("ORDER_WRITTEN_OFF").size)
    }

    @Test
    fun `an OPEN order without bill keeps the phase 4 behaviour - shortfall from billable items`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        forceClose(guest.sessionId).andExpect(status().isOk)
        assertEquals("WRITTEN_OFF", orderStatusOf(guest.sessionId))
        assertEquals(159_000L, long(jdbc.queryForMap("SELECT shortfall_amount FROM orders WHERE id = ?", orderId), "shortfall_amount"))
        assertEquals(0L, count("SELECT count(*) FROM bill"))
    }
}
