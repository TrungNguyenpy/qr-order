package com.qrorder.billing

import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.BillingIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Staff mở lại phiên (P1, BR-P08 a, FR-PAY-06, SRS §6.16, §9.9 luồng 4): chỉ khi Bill ACTIVE chưa có Payment SUCCESS
 * và không có Payment PENDING. Bill VOIDED (SESSION_REOPEN), Order OPEN, phiên OPEN, bàn OCCUPIED, audit; sau đó Staff
 * Tính tiền lại tạo Bill mới.
 */
class SessionReopenIT : BillingIntegrationTestBase() {

    private fun reopen(sessionId: Long, token: String = cashier.accessToken, reason: String? = "Khách gọi thêm món") =
        api.post("/api/v1/sessions/$sessionId/reopen", token, mapOf("reason" to reason))

    @Test
    fun `staff reopens a billing session - bill VOIDED, order and session OPEN, table OCCUPIED, guest orders again, new bill`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val firstBill = createBillOk(orderId)
        val firstId = firstBill["id"].asLong()

        val result = data(reopen(guest.sessionId).andExpect(status().isOk))
        assertEquals("OPEN", result["sessionStatus"].asText())
        assertEquals("OPEN", result["orderStatus"].asText())
        assertEquals("OCCUPIED", result["tableStatus"].asText())
        assertEquals(firstId, result["voidedBillId"].asLong())

        val bill = billRow(firstId)
        assertEquals("VOIDED", bill["status"])
        assertEquals("SESSION_REOPEN", bill["void_type"])
        assertEquals("Khách gọi thêm món", bill["void_reason"])
        assertEquals(cashier.id, long(bill, "voided_by"))
        assertEquals("OPEN", orderStatusOf(guest.sessionId))
        assertEquals("OPEN", sessionStatus(guest.sessionId))
        assertNull(jdbc.queryForObject("SELECT billing_started_at FROM table_session WHERE id = ?", java.time.OffsetDateTime::class.java, guest.sessionId))
        assertEquals("OCCUPIED", tableStatus(tableId("T01")))
        val audit = api.auditRows("SESSION_REOPENED").single()
        assertEquals("Khách gọi thêm món", audit["reason"])
        assertEquals(cashier.id, audit["actor_user_id"])
        assertTrue(stomp.on("/topic/session/${guest.sessionId}").any { it["change"].asText() == "REOPENED" })
        assertTrue(stomp.on("/topic/session/${guest.sessionId}").any { it["type"].asText() == "BILL_CHANGED" && it["change"].asText() == "BILL_VOIDED" })

        // Khách gọi thêm, Staff tính tiền lại: Bill mới, mã mới, không phải Bill thay thế của Manager.
        orderConfirmed(guest, coffeeSmall())
        val second = createBillOk(orderId)
        assertTrue(second["billNumber"].asText() != firstBill["billNumber"].asText())
        assertTrue(second["replacesBillId"].isNull)
        assertEquals(188_000L, second["totalAmount"].asLong(), "159000 + 29000")
        assertEquals(2L, count("SELECT count(*) FROM bill WHERE order_id = ?", orderId))
    }

    @Test
    fun `reopen is refused when the bill has a SUCCESS or PENDING payment, FAILED does not block`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()

        val pendingId = payOk(billId, 50_000, "PENDING", "CARD_POS")["payment"]["id"].asLong()
        reopen(guest.sessionId).isError(409, "PENDING_PAYMENT_EXISTS")
        confirmPayment(pendingId, "FAILED", "Thẻ bị từ chối").andExpect(status().isOk)

        payOk(billId, 10_000)
        reopen(guest.sessionId).isError(409, "BILL_HAS_PAYMENTS")
        reopen(guest.sessionId, manager.accessToken).isError(409, "BILL_HAS_PAYMENTS")
        assertEquals("BILLING", sessionStatus(guest.sessionId))
        assertEquals("ACTIVE", billRow(billId)["status"])
        assertEquals(0, api.auditRows("SESSION_REOPENED").size)
    }

    @Test
    fun `a bill with only a FAILED payment can be reopened`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        val pendingId = payOk(billId, 159_000, "PENDING", "BANK_TRANSFER")["payment"]["id"].asLong()
        confirmPayment(pendingId, "FAILED", "Không thấy tiền về").andExpect(status().isOk)
        reopen(guest.sessionId).andExpect(status().isOk)
        assertEquals("VOIDED", billRow(billId)["status"])
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM payment_transaction WHERE id = ?", String::class.java, pendingId))
    }

    @Test
    fun `reopen needs PAYMENT_PROCESS, a reason and a BILLING session`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        reopen(guest.sessionId).isError(409, "SESSION_NOT_BILLING")
        createBillOk(orderId)
        reopen(guest.sessionId, staff.accessToken).isError(403, "PERMISSION_DENIED")
        reopen(guest.sessionId, reason = " ").isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/sessions/${guest.sessionId}/reopen", cashier.accessToken, mapOf("reason" to "x", "version" to 999))
            .isError(409, "CONCURRENT_MODIFICATION")
        reopen(guest.sessionId, manager.accessToken).andExpect(status().isOk)
        reopen(guest.sessionId).isError(409, "SESSION_NOT_BILLING")
    }

    @Test
    fun `a zero-total PAID bill whose items are still cooking can be reopened`() {
        val guest = guestAt("T04")
        orderConfirmed(guest, line("Trà đá"))
        val bill = createBillOk(orderIdOf(guest.sessionId))
        assertEquals("PAID", bill["paymentStatus"].asText())
        assertEquals("BILLING", sessionStatus(guest.sessionId), "Trà đá chưa phục vụ")
        reopen(guest.sessionId).andExpect(status().isOk)
        assertEquals("OPEN", orderStatusOf(guest.sessionId))
        assertEquals("VOIDED", billRow(bill["id"].asLong())["status"])
    }
}
