package com.qrorder.support

import com.fasterxml.jackson.databind.JsonNode
import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.UserRole
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Nền cho integration test Giai đoạn 5a (Bill, thanh toán, đóng phiên, mở lại phiên, force-close có Bill, ServiceCall):
 * MockMvc + PostgreSQL thật (COMMIT thật - trigger DEFERRABLE trg_bill_invariants chạy lúc COMMIT), bộ ghi STOMP.
 * Seed V2: VAT 8%, Phở bò tái 65.000 (cỡ Nhỏ phụ thu 0), Cà phê sữa đá 29.000, Trà đá 0.
 */
abstract class BillingIntegrationTestBase : OrderIntegrationTestBase() {

    /** Thu ngân: STAFF được cấp PAYMENT_PROCESS (Manager cấp). */
    protected val cashier: LoggedInUser by lazy {
        api.loggedIn("cashier_bill", UserRole.STAFF).also { fx.grant(it.id, Permission.PAYMENT_PROCESS, manager.id) }
    }

    protected fun orderIdOf(sessionId: Long): Long =
        jdbc.queryForObject("SELECT id FROM orders WHERE session_id = ?", Long::class.java, sessionId)!!

    /** Đưa món CONFIRMED qua bếp tới [target] (PREPARING, READY hoặc SERVED). */
    protected fun advance(itemId: Long, target: String) {
        val path = listOf("PREPARING", "READY", "SERVED")
        val current = itemStatus(itemId)
        for (step in path.dropWhile { it != nextOf(current) }) {
            if (step == "SERVED") {
                api.post("/api/v1/orders/items/$itemId/serve", staff.accessToken, null).andExpect(status().isOk)
            } else {
                api.post("/api/v1/kitchen/items/$itemId/status", kitchen.accessToken, mapOf("status" to step)).andExpect(status().isOk)
            }
            if (step == target) return
        }
    }

    private fun nextOf(status: String): String = when (status) {
        "CONFIRMED" -> "PREPARING"
        "PREPARING" -> "READY"
        "READY" -> "SERVED"
        else -> error("Món đang $status, không đi tiếp được")
    }

    /** Khách đặt một đợt (đã xác nhận) gồm [lines]; trả id các món. */
    protected fun orderConfirmed(guest: GuestCtx, vararg lines: Map<String, Any?>): List<Long> {
        val order = placeOk(guest, *lines)
        val batch = order["batches"].last()
        val batchId = batch["id"].asLong()
        if (batchStatus(batchId) == "PENDING") {
            api.post("/api/v1/orders/batches/$batchId/confirm", staff.accessToken, null).andExpect(status().isOk)
        }
        return batch["items"].map { it["id"].asLong() }
    }

    /**
     * Cà phê sữa đá cỡ "Nhỏ", đường "50%" (phụ thu 0): seed V2 gắn 2 nhóm bắt buộc Kích cỡ + Mức đường cho món này,
     * đặt không kèm option sẽ bị 422 INVALID_OPTION_SELECTION (BR-O03).
     */
    protected fun coffeeSmall(quantity: Int = 1) = line(coffee, quantity, listOf("Kích cỡ" to "Nhỏ", "Mức đường" to "50%"))

    /** Một phiên T01 có 2 Phở nhỏ + 1 Cà phê nhỏ (159.000) đã xác nhận. Trả (khách, orderId, id các món). */
    protected fun tableWithStandardOrder(tableCode: String = "T01"): Triple<GuestCtx, Long, List<Long>> {
        val guest = guestAt(tableCode)
        val items = orderConfirmed(guest, phoSmall(2), coffeeSmall())
        return Triple(guest, orderIdOf(guest.sessionId), items)
    }

    protected fun createBill(orderId: Long, token: String = cashier.accessToken, discount: Map<String, Any?>? = null): ResultActions =
        api.post("/api/v1/orders/$orderId/bill", token, if (discount == null) null else mapOf("discount" to discount))

    protected fun createBillOk(orderId: Long, token: String = cashier.accessToken, discount: Map<String, Any?>? = null): JsonNode =
        data(createBill(orderId, token, discount).andExpect(status().isCreated))

    protected fun pay(
        billId: Long,
        amount: Long,
        txnStatus: String = "SUCCESS",
        method: String = "CASH",
        requestId: UUID = UUID.randomUUID(),
        token: String = cashier.accessToken,
        providerTxnId: String? = null,
    ): ResultActions = api.post(
        "/api/v1/bills/$billId/payments", token,
        mapOf("requestId" to requestId.toString(), "method" to method, "amount" to amount, "status" to txnStatus, "providerTxnId" to providerTxnId),
    )

    protected fun payOk(billId: Long, amount: Long, txnStatus: String = "SUCCESS", method: String = "CASH"): JsonNode =
        data(pay(billId, amount, txnStatus, method).andExpect(status().isCreated))

    protected fun confirmPayment(paymentId: Long, result: String, failureReason: String? = null, token: String = cashier.accessToken): ResultActions =
        api.post("/api/v1/payments/$paymentId/confirm", token, mapOf("result" to result, "failureReason" to failureReason))

    // ------------------------------------------------------------------ GĐ5b: Void, Refund, Bill thay thế

    protected fun requestVoid(billId: Long, reason: String? = "Sai món", token: String = manager.accessToken): ResultActions =
        api.post("/api/v1/bills/$billId/void", token, mapOf("reason" to reason))

    protected fun requestVoidOk(billId: Long, token: String = manager.accessToken): JsonNode =
        data(requestVoid(billId, token = token).andExpect(status().isOk))

    protected fun withdrawVoid(billId: Long, reason: String? = "Khách đồng ý giữ Bill", token: String = manager.accessToken): ResultActions =
        api.post("/api/v1/bills/$billId/void/withdraw", token, mapOf("reason" to reason))

    protected fun refund(
        billId: Long,
        amount: Long,
        txnStatus: String = "SUCCESS",
        method: String = "CASH",
        requestId: UUID = UUID.randomUUID(),
        token: String = manager.accessToken,
        reason: String? = "Hoàn tiền do Void",
    ): ResultActions = api.post(
        "/api/v1/bills/$billId/refunds", token,
        mapOf("requestId" to requestId.toString(), "method" to method, "amount" to amount, "status" to txnStatus, "reason" to reason),
    )

    protected fun refundOk(billId: Long, amount: Long, txnStatus: String = "SUCCESS"): JsonNode =
        data(refund(billId, amount, txnStatus).andExpect(status().isCreated))

    protected fun confirmRefund(refundId: Long, result: String, failureReason: String? = null, token: String = manager.accessToken): ResultActions =
        api.post("/api/v1/refunds/$refundId/confirm", token, mapOf("result" to result, "failureReason" to failureReason))

    protected fun createReplacement(orderId: Long, token: String = manager.accessToken, discount: Map<String, Any?>? = null): ResultActions =
        api.post("/api/v1/orders/$orderId/replacement-bill", token, if (discount == null) null else mapOf("discount" to discount))

    protected fun orderRow(orderId: Long): Map<String, Any?> =
        jdbc.queryForMap("SELECT status, status_before_void, needs_attention, attention_reason, shortfall_amount FROM orders WHERE id = ?", orderId)

    /** Bàn có order chuẩn (159.000) đã được thu đủ; mọi món đã phục vụ nên phiên CLOSED. Trả (khách, orderId, billId). */
    protected fun paidAndClosed(tableCode: String = "T01"): Triple<GuestCtx, Long, Long> {
        val (guest, orderId, items) = tableWithStandardOrder(tableCode)
        items.forEach { advance(it, "SERVED") }
        val billId = createBillOk(orderId)["id"].asLong()
        payOk(billId, 159_000)
        check(sessionStatus(guest.sessionId) == "CLOSED") { "Phiên phải CLOSED sau khi thu đủ" }
        return Triple(guest, orderId, billId)
    }

    protected fun billRow(billId: Long): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM bill WHERE id = ?", billId)

    protected fun long(row: Map<String, Any?>, column: String): Long = (row[column] as Number).toLong()

    protected fun vat(amount: Long, rate: String = "0.0800"): Long = fx.vatOf(amount, rate)

    /** Ngày nghiệp vụ hôm nay theo Asia/Ho_Chi_Minh (YYYYMMDD) theo đồng hồ DB - tiền tố mã Bill/phiếu (BR-P12). */
    protected fun businessDate(): String =
        jdbc.queryForObject("SELECT to_char(now() AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYYMMDD')", String::class.java)!!
}
