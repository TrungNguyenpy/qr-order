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
 * Thanh toán (FR-PAY-04, FR-PAY-12, BR-P10..P12, DEC-11 C): chia tiền nhiều lần, PENDING -> SUCCESS/FAILED, mã phiếu chỉ
 * khi SUCCESS, không thu vượt (kể cả đồng thời), idempotent theo requestId, dữ liệu in phiếu nội bộ.
 */
class PaymentFlowIT : BillingIntegrationTestBase() {

    private fun billFor(tableCode: String = "T01"): Pair<Long, Long> {
        val (guest, orderId, _) = tableWithStandardOrder(tableCode)
        return createBillOk(orderId)["id"].asLong() to guest.sessionId
    }

    @Test
    fun `split payment - cash then card PENDING confirmed SUCCESS, receipt numbers only on SUCCESS, bill PAID`() {
        val (billId, sessionId) = billFor()

        val first = payOk(billId, 100_000)
        assertEquals("SUCCESS", first["payment"]["status"].asText())
        val receipt1 = first["payment"]["receiptNumber"].asText()
        assertEquals("${businessDate()}-0001", receipt1)
        assertEquals("PARTIALLY_PAID", first["bill"]["paymentStatus"].asText())
        assertEquals(100_000L, first["bill"]["paidAmount"].asLong())
        assertEquals(59_000L, first["bill"]["remainingAmount"].asLong())
        assertEquals("LOCKED", first["orderStatus"].asText())

        val pending = payOk(billId, 59_000, "PENDING", "CARD_POS")
        val pendingId = pending["payment"]["id"].asLong()
        assertTrue(pending["payment"]["receiptNumber"].isNull, "PENDING không có mã phiếu (BR-P12)")
        assertEquals("PARTIALLY_PAID", pending["bill"]["paymentStatus"].asText())
        assertEquals(0L, pending["bill"]["remainingAmount"].asLong(), "PENDING giữ chỗ phần còn lại (BR-P10)")
        pay(billId, 1).isError(422, "PAYMENT_EXCEEDS_REMAINING")

        val confirmed = data(confirmPayment(pendingId, "SUCCESS").andExpect(status().isOk))
        assertEquals("${businessDate()}-0002", confirmed["payment"]["receiptNumber"].asText())
        assertEquals("PAID", confirmed["bill"]["paymentStatus"].asText())
        assertEquals("PAID", confirmed["orderStatus"].asText())
        assertEquals("BILLING", confirmed["sessionStatus"].asText(), "món chưa phục vụ xong nên phiên chưa đóng")
        assertEquals(false, confirmed["sessionClosed"].asBoolean())
        val bill = billRow(billId)
        assertEquals("PAID", bill["payment_status"])
        assertTrue(bill["paid_at"] != null)
        assertEquals("PAID", orderStatusOf(sessionId))

        // Lịch sử trạng thái do trigger ghi (FR-PAY-04).
        val history = data(api.get("/api/v1/payments/$pendingId", staff.accessToken).andExpect(status().isOk))["history"]
        assertEquals(listOf("PENDING", "SUCCESS"), history.map { it["toStatus"].asText() })
        assertEquals(1, api.auditRows("BILL_PAID").size)
        assertTrue(stomp.on("/topic/session/$sessionId").any { it["type"].asText() == "BILL_CHANGED" && it["change"].asText() == "BILL_PAID" })
        // Bill PAID: không thu thêm.
        pay(billId, 1).isError(422, "PAYMENT_EXCEEDS_REMAINING")
    }

    @Test
    fun `FAILED needs a reason, gets no receipt, releases the reserved amount, and is final`() {
        val (billId, _) = billFor()
        val pendingId = payOk(billId, 159_000, "PENDING", "BANK_TRANSFER")["payment"]["id"].asLong()
        pay(billId, 1).isError(422, "PAYMENT_EXCEEDS_REMAINING")

        confirmPayment(pendingId, "FAILED").isError(400, "VALIDATION_FAILED")
        val failed = data(confirmPayment(pendingId, "FAILED", "Chưa nhận được tiền").andExpect(status().isOk))
        assertEquals("FAILED", failed["payment"]["status"].asText())
        assertTrue(failed["payment"]["receiptNumber"].isNull)
        assertEquals("UNPAID", failed["bill"]["paymentStatus"].asText())
        assertEquals(159_000L, failed["bill"]["remainingAmount"].asLong())

        // Xác nhận lại cùng kết quả: idempotent; đổi kết quả: 409 (BR-P10 - bất biến).
        assertEquals(true, data(confirmPayment(pendingId, "FAILED", "lần 2").andExpect(status().isOk))["replayed"].asBoolean())
        confirmPayment(pendingId, "SUCCESS").isError(409, "TRANSACTION_IMMUTABLE")
        api.get("/api/v1/payments/$pendingId/receipt", staff.accessToken).isError(409, "RECEIPT_NOT_AVAILABLE")
        val reasons = jdbc.queryForList(
            "SELECT to_status, reason FROM transaction_status_history WHERE payment_transaction_id = ? ORDER BY id", pendingId,
        )
        assertEquals("FAILED", reasons.last()["to_status"])
        assertEquals("Chưa nhận được tiền", reasons.last()["reason"])
    }

    @Test
    fun `requestId makes recording idempotent - same body replays, different body is rejected`() {
        val (billId, _) = billFor()
        val requestId = UUID.randomUUID()
        val first = data(pay(billId, 40_000, requestId = requestId).andExpect(status().isCreated))
        val again = data(pay(billId, 40_000, requestId = requestId).andExpect(status().isOk))
        assertEquals(first["payment"]["id"].asLong(), again["payment"]["id"].asLong())
        assertEquals(true, again["replayed"].asBoolean())
        assertEquals(first["payment"]["receiptNumber"].asText(), again["payment"]["receiptNumber"].asText())
        pay(billId, 41_000, requestId = requestId).isError(409, "DUPLICATE_REQUEST_ID")
        assertEquals(1L, count("SELECT count(*) FROM payment_transaction WHERE bill_id = ?", billId))
        assertEquals("PARTIALLY_PAID", billRow(billId)["payment_status"])
    }

    @Test
    fun `concurrent payments of the full remaining amount - exactly one succeeds, never over-collected`() {
        val (billId, _) = billFor()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val results = (1..4).map {
                pool.submit(Callable {
                    start.await()
                    pay(billId, 159_000).andReturn().response.status
                })
            }
            start.countDown()
            val statuses = results.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, statuses.count { it == 201 }, statuses.toString())
            assertEquals(3, statuses.count { it == 422 }, statuses.toString())
        } finally {
            pool.shutdownNow()
        }
        assertEquals(159_000L, count("SELECT coalesce(sum(amount), 0) FROM payment_transaction WHERE bill_id = ? AND status = 'SUCCESS'", billId))
        assertEquals("PAID", billRow(billId)["payment_status"])
    }

    @Test
    fun `concurrent split payments in different amounts never exceed the total`() {
        val (billId, _) = billFor()
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val results = (1..6).map {
                pool.submit(Callable {
                    start.await()
                    pay(billId, 50_000).andReturn().response.status
                })
            }
            start.countDown()
            val statuses = results.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(3, statuses.count { it == 201 }, "3 x 50000 <= 159000 < 4 x 50000: $statuses")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(150_000L, count("SELECT sum(amount) FROM payment_transaction WHERE bill_id = ? AND status = 'SUCCESS'", billId))
        assertEquals("PARTIALLY_PAID", billRow(billId)["payment_status"])
        val numbers = jdbc.queryForList("SELECT receipt_number FROM payment_transaction WHERE bill_id = ? ORDER BY receipt_number", String::class.java, billId)
        assertEquals((1..3).map { "${businessDate()}-000$it" }, numbers, "mã phiếu liên tục, không trùng (BR-P12)")
    }

    @Test
    fun `provider transaction id is optional, defaults provider to the method and must be unique`() {
        val (billId, _) = billFor()
        val first = data(pay(billId, 10_000, method = "BANK_TRANSFER", providerTxnId = "FT2611210001").andExpect(status().isCreated))
        assertEquals("BANK_TRANSFER", first["payment"]["provider"].asText())
        assertEquals("FT2611210001", first["payment"]["providerTxnId"].asText())
        pay(billId, 10_000, method = "BANK_TRANSFER", providerTxnId = "FT2611210001").isError(409, "DUPLICATE_PROVIDER_TXN")
        val plain = payOk(billId, 10_000)
        assertTrue(plain["payment"]["provider"].isNull)
    }

    @Test
    fun `validation and permissions - amount must be positive, STAFF without PAYMENT_PROCESS gets 403`() {
        val (billId, _) = billFor()
        pay(billId, 0).isError(400, "VALIDATION_FAILED")
        pay(billId, -5).isError(400, "VALIDATION_FAILED")
        pay(billId, 1_000, token = staff.accessToken).isError(403, "PERMISSION_DENIED")
        pay(billId, 1_000, token = admin.accessToken).isError(403, "PERMISSION_DENIED")
        pay(999_999, 1_000).isError(404, "RESOURCE_NOT_FOUND")
        assertEquals(0L, count("SELECT count(*) FROM payment_transaction"))
        pay(billId, 1_000, token = manager.accessToken).andExpect(status().isCreated)
    }

    @Test
    fun `internal receipt data for a SUCCESS payment - FR-PAY-12`() {
        val (billId, _) = billFor()
        payOk(billId, 59_000)
        val secondId = payOk(billId, 100_000, method = "CARD_POS")["payment"]["id"].asLong()
        val receipt = data(api.get("/api/v1/payments/$secondId/receipt", staff.accessToken).andExpect(status().isOk))

        assertEquals("Nhà hàng Test", receipt["header"]["restaurantName"].asText())
        assertEquals("${businessDate()}-0002", receipt["receiptNumber"].asText())
        assertTrue(receipt["billNumber"].asText().startsWith("BILL-"))
        assertEquals("T01", receipt["tableCode"].asText())
        assertEquals("CARD_POS", receipt["method"].asText())
        assertEquals(100_000L, receipt["amount"].asLong())
        assertEquals(59_000L, receipt["paidBefore"].asLong())
        assertEquals(159_000L, receipt["paidAfter"].asLong())
        assertEquals(0L, receipt["remainingAfter"].asLong())
        assertEquals(159_000L, receipt["totalAmount"].asLong())
        assertEquals(11_778L, receipt["vatAmount"].asLong())
        assertEquals(2, receipt["lines"].size())
        assertEquals("Nhân viên cashier_bill", receipt["cashierName"].asText())
        assertTrue(receipt["footer"].asText().contains("nội bộ"))
        assertNull(receipt["header"]["phone"].takeIf { !it.isNull })
    }
}
