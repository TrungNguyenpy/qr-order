package com.qrorder.billing

import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.BillingIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GĐ5b - Void có hoàn tiền, Refund và rút Void (BR-P09, BR-P13, BR-P14, BR-P17, O1, P2; SRS §6.12, §6.13, §12.1, §12.3).
 * Bill chuẩn 159.000 (TPS khi thu đủ).
 */
class VoidRefundIT : BillingIntegrationTestBase() {

    @Test
    fun `void with nothing collected - bill VOIDED at once, order LOCKED awaiting replacement, session and table unchanged`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()

        val result = requestVoidOk(billId)
        assertEquals(true, result["voidCompleted"].asBoolean())
        assertEquals("VOIDED", result["bill"]["status"].asText())
        assertEquals(true, result["bill"]["orderNeedsAttention"].asBoolean())
        val bill = billRow(billId)
        assertEquals("VOIDED", bill["status"])
        assertEquals("MANAGER_VOID", bill["void_type"])
        assertEquals("Sai món", bill["void_reason"])
        assertEquals(manager.id, long(bill, "void_requested_by"))
        val order = orderRow(orderId)
        assertEquals("LOCKED", order["status"])
        assertEquals(true, order["needs_attention"])
        assertEquals("AWAITING_REPLACEMENT_BILL", order["attention_reason"])
        assertEquals("BILLING", sessionStatus(guest.sessionId), "BR-P09 - phiên không đổi")
        assertEquals("PAYMENT_PENDING", tableStatus(tableId("T01")))
        assertEquals("Sai món", api.auditRows("BILL_VOID_REQUESTED").single()["reason"])
        assertEquals(1, api.auditRows("BILL_VOIDED").size)
        assertTrue(stomp.on("/topic/session/${guest.sessionId}").any { it["type"].asText() == "BILL_CHANGED" && it["change"].asText() == "BILL_VOIDED" })

        // FR-PAY-13: hiện trong danh sách Cần xử lý của Staff (chỉ xem) và Manager.
        val attention = data(api.get("/api/v1/billing/attention", staff.accessToken).andExpect(status().isOk))
        assertEquals("AWAITING_REPLACEMENT_BILL", attention.single()["type"].asText())
        assertEquals(orderId, attention.single()["orderId"].asLong())
        assertEquals("T01", attention.single()["tableCode"].asText())
    }

    @Test
    fun `void after the session closed - VOID_PENDING, order PAID becomes LOCKED, no more payments and no new bill`() {
        val (guest, orderId, billId) = paidAndClosed()

        val result = requestVoidOk(billId)
        assertEquals(false, result["voidCompleted"].asBoolean())
        assertEquals("VOID_PENDING", result["bill"]["status"].asText())
        assertEquals(159_000L, result["bill"]["remainingRefundable"].asLong())
        val order = orderRow(orderId)
        assertEquals("LOCKED", order["status"])
        assertEquals("PAID", order["status_before_void"])
        assertEquals(false, order["needs_attention"])
        assertEquals("CLOSED", sessionStatus(guest.sessionId), "phiên đã CLOSED giữ nguyên")
        assertEquals("CLEANING", tableStatus(tableId("T01")))

        pay(billId, 1_000).isError(409, "PAYMENT_BILL_NOT_ACTIVE")
        createReplacement(orderId).isError(409, "BILL_VOID_PENDING")
        requestVoid(billId).isError(409, "BILL_VOID_PENDING")
        val attention = data(api.get("/api/v1/billing/attention", manager.accessToken))
        assertEquals("BILL_VOID_PENDING", attention.single()["type"].asText())
        assertEquals(159_000L, attention.single()["remainingRefundable"].asLong())
    }

    @Test
    fun `refund limits follow remaining_refundable = TPS - TRS - TRP - SRS 12-1`() {
        val (_, orderId, billId) = paidAndClosed()
        requestVoidOk(billId)

        // #2: TRS 59.000 -> còn hoàn tối đa 100.000; yêu cầu 100.001 bị từ chối.
        val first = refundOk(billId, 59_000)
        assertEquals("RF-${businessDate()}-0001", first["refund"]["receiptNumber"].asText())
        assertEquals(100_000L, first["summary"]["remainingRefundable"].asLong())
        assertEquals(100_000L, first["summary"]["netCollected"].asLong())
        assertEquals("PARTIALLY_PAID", first["summary"]["paymentStatus"].asText(), "BR-P11 - thu ròng = TPS - TRS")
        assertEquals("VOID_PENDING", first["summary"]["billStatus"].asText())
        refund(billId, 100_001).isError(422, "REFUND_EXCEEDS_REFUNDABLE")

        // #4: TRP 60.000 -> còn tối đa 40.000; #3: TRP thêm 40.000 -> không hoàn thêm được, Void chưa hoàn tất.
        val pendingA = refundOk(billId, 60_000, "PENDING")
        assertTrue(pendingA["refund"]["receiptNumber"].isNull, "PENDING không có mã phiếu")
        assertEquals(40_000L, pendingA["summary"]["remainingRefundable"].asLong())
        refund(billId, 40_001).isError(422, "REFUND_EXCEEDS_REFUNDABLE")
        val pendingB = refundOk(billId, 40_000, "PENDING")
        assertEquals(0L, pendingB["summary"]["remainingRefundable"].asLong())
        refund(billId, 1).isError(422, "REFUND_EXCEEDS_REFUNDABLE")
        assertEquals("VOID_PENDING", billRow(billId)["status"])

        // #6: FAILED không tính - remaining tăng lại 60.000.
        val pendingAId = pendingA["refund"]["id"].asLong()
        confirmRefund(pendingAId, "FAILED").isError(400, "VALIDATION_FAILED")
        val failed = data(confirmRefund(pendingAId, "FAILED", "Khách chưa nhận được").andExpect(status().isOk))
        assertEquals(60_000L, failed["summary"]["remainingRefundable"].asLong())
        assertTrue(failed["refund"]["receiptNumber"].isNull)
        confirmRefund(pendingAId, "SUCCESS").isError(409, "TRANSACTION_IMMUTABLE")

        val confirmedB = data(confirmRefund(pendingB["refund"]["id"].asLong(), "SUCCESS").andExpect(status().isOk))
        assertEquals("RF-${businessDate()}-0002", confirmedB["refund"]["receiptNumber"].asText())
        assertEquals(false, confirmedB["voidCompleted"].asBoolean())

        // #5: hoàn đủ -> Void hoàn tất, Bill VOIDED, Order LOCKED chờ Bill thay thế.
        val last = refundOk(billId, 60_000)
        assertEquals(true, last["voidCompleted"].asBoolean())
        assertEquals("VOIDED", last["summary"]["billStatus"].asText())
        assertEquals("UNPAID", last["summary"]["paymentStatus"].asText(), "DB-04: hoàn đủ thì UNPAID")
        assertEquals(0L, last["summary"]["netCollected"].asLong())
        assertEquals(true, last["orderNeedsAttention"].asBoolean())
        val order = orderRow(orderId)
        assertEquals("LOCKED", order["status"])
        assertNull(order["status_before_void"])
        assertEquals("AWAITING_REPLACEMENT_BILL", order["attention_reason"])
        assertEquals(1L, count("SELECT count(*) FROM payment_transaction WHERE bill_id = ? AND status = 'SUCCESS'", billId), "Payment giữ nguyên")
        assertEquals(1, api.auditRows("BILL_VOIDED").size)
        assertEquals(4, api.auditRows("REFUND_CREATED").size)
        refund(billId, 1).isError(409, "REFUND_REQUIRES_VOID_PENDING")
    }

    @Test
    fun `refund needs a VOID_PENDING bill and is idempotent by requestId`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        payOk(billId, 59_000)
        refund(billId, 1_000).isError(409, "REFUND_REQUIRES_VOID_PENDING")
        assertEquals(0L, count("SELECT count(*) FROM refund_transaction"))

        requestVoidOk(billId)
        assertEquals("BILLING", sessionStatus(guest.sessionId))
        val requestId = UUID.randomUUID()
        val first = data(refund(billId, 20_000, requestId = requestId).andExpect(status().isCreated))
        val again = data(refund(billId, 20_000, requestId = requestId).andExpect(status().isOk))
        assertEquals(first["refund"]["id"].asLong(), again["refund"]["id"].asLong())
        assertEquals(true, again["replayed"].asBoolean())
        refund(billId, 21_000, requestId = requestId).isError(409, "DUPLICATE_REQUEST_ID")
        refund(billId, 1_000, reason = " ").isError(400, "VALIDATION_FAILED")
        refund(billId, 0).isError(400, "VALIDATION_FAILED")
        assertEquals(1L, count("SELECT count(*) FROM refund_transaction"))
    }

    @Test
    fun `concurrent refunds of the full refundable amount - exactly one succeeds`() {
        val (_, _, billId) = paidAndClosed()
        requestVoidOk(billId)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val results = (1..4).map {
                pool.submit(Callable {
                    start.await()
                    refund(billId, 159_000, txnStatus = "PENDING").andReturn().response.status
                })
            }
            start.countDown()
            val statuses = results.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, statuses.count { it == 201 }, statuses.toString())
            assertEquals(3, statuses.count { it == 422 }, statuses.toString())
        } finally {
            pool.shutdownNow()
        }
        assertEquals(159_000L, count("SELECT sum(amount) FROM refund_transaction WHERE bill_id = ?", billId))
    }

    @Test
    fun `withdraw void - refused with PENDING or SUCCESS refunds, allowed with only FAILED ones, order back to its previous status`() {
        val (_, orderId, billId) = paidAndClosed()
        requestVoidOk(billId)

        // #4: Refund PENDING chặn rút Void.
        val pendingId = refundOk(billId, 10_000, "PENDING")["refund"]["id"].asLong()
        withdrawVoid(billId).isError(409, "VOID_HAS_REFUNDS")
        // #5: chỉ còn Refund FAILED -> được rút; Bill ACTIVE, Order về PAID.
        confirmRefund(pendingId, "FAILED", "Nhập nhầm").andExpect(status().isOk)
        withdrawVoid(billId, reason = " ").isError(400, "VALIDATION_FAILED")
        val withdrawn = data(withdrawVoid(billId).andExpect(status().isOk))
        assertEquals("ACTIVE", withdrawn["bill"]["status"].asText())
        assertEquals("PAID", withdrawn["bill"]["paymentStatus"].asText())
        val bill = billRow(billId)
        assertEquals(manager.id, long(bill, "void_withdrawn_by"))
        assertEquals("Khách đồng ý giữ Bill", bill["void_withdraw_reason"])
        val order = orderRow(orderId)
        assertEquals("PAID", order["status"])
        assertNull(order["status_before_void"])
        assertEquals("Khách đồng ý giữ Bill", api.auditRows("BILL_VOID_WITHDRAWN").single()["reason"])
        withdrawVoid(billId).isError(409, "BILL_NOT_VOID_PENDING")

        // #4 với Refund SUCCESS: đã hoàn một phần thì phải hoàn tiếp, không rút được.
        requestVoidOk(billId)
        refundOk(billId, 1_000)
        withdrawVoid(billId).isError(409, "VOID_HAS_REFUNDS")
        assertEquals("VOID_PENDING", billRow(billId)["status"])
    }

    @Test
    fun `void is blocked by a PENDING payment and needs BILL_VOID - REFUND_RECORD for refunds`() {
        val (_, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        val pendingId = payOk(billId, 59_000, "PENDING", "CARD_POS")["payment"]["id"].asLong()
        requestVoid(billId).isError(409, "PENDING_PAYMENT_EXISTS")

        // SRS 12.3 #10: ADMIN không có BILL_VOID, Staff/thu ngân không có quyền -> 403.
        requestVoid(billId, token = admin.accessToken).isError(403, "PERMISSION_DENIED")
        requestVoid(billId, token = staff.accessToken).isError(403, "PERMISSION_DENIED")
        requestVoid(billId, token = cashier.accessToken).isError(403, "PERMISSION_DENIED")
        requestVoid(billId, reason = "").isError(400, "VALIDATION_FAILED")

        confirmPayment(pendingId, "SUCCESS").andExpect(status().isOk)
        requestVoidOk(billId)
        refund(billId, 1_000, token = cashier.accessToken).isError(403, "PERMISSION_DENIED")
        refund(billId, 1_000, token = admin.accessToken).isError(403, "PERMISSION_DENIED")
        withdrawVoid(billId, token = cashier.accessToken).isError(403, "PERMISSION_DENIED")
        assertEquals("VOID_PENDING", billRow(billId)["status"])
    }

    @Test
    fun `refund receipt RF and refund history - FR-PAY-12`() {
        val (_, _, billId) = paidAndClosed()
        requestVoidOk(billId)
        val pending = refundOk(billId, 159_000, "PENDING")["refund"]["id"].asLong()
        api.get("/api/v1/refunds/$pending/receipt", staff.accessToken).isError(409, "RECEIPT_NOT_AVAILABLE")
        confirmRefund(pending, "SUCCESS").andExpect(status().isOk)

        val receipt = data(api.get("/api/v1/refunds/$pending/receipt", staff.accessToken).andExpect(status().isOk))
        assertEquals("RF-${businessDate()}-0001", receipt["receiptNumber"].asText())
        assertEquals("Nhà hàng Test", receipt["header"]["restaurantName"].asText())
        assertEquals(159_000L, receipt["amount"].asLong())
        assertEquals(159_000L, receipt["totalPaidSuccess"].asLong())
        assertEquals(0L, receipt["refundedBefore"].asLong())
        assertEquals(159_000L, receipt["refundedAfter"].asLong())
        assertEquals(0L, receipt["remainingToRefund"].asLong())
        assertEquals("Sai món", receipt["voidReason"].asText())
        assertEquals("VOIDED", receipt["billStatus"].asText())

        val history = data(api.get("/api/v1/refunds/$pending", staff.accessToken))["history"]
        assertEquals(listOf("PENDING", "SUCCESS"), history.map { it["toStatus"].asText() })
        val bill = data(api.get("/api/v1/bills/$billId", staff.accessToken))
        assertEquals(159_000L, bill["refundedAmount"].asLong())
        assertEquals(1, bill["refunds"].size())
    }
}
