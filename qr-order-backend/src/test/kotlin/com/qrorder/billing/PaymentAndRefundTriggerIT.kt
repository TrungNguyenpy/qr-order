package com.qrorder.billing

import com.qrorder.auth.domain.UserRole
import com.qrorder.billing.domain.Bill
import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.domain.PaymentMethod
import com.qrorder.billing.domain.PaymentStatus
import com.qrorder.billing.domain.PaymentTransaction
import com.qrorder.billing.domain.RefundTransaction
import com.qrorder.billing.domain.TransactionStatus
import com.qrorder.billing.domain.TransactionStatusHistory
import com.qrorder.billing.domain.VoidType
import com.qrorder.common.config.BusinessTime
import com.qrorder.common.error.ErrorCode
import com.qrorder.support.IntegrationTestBase
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bất biến tiền ở tầng database (BR-P10..P13) đi qua JPA entity và COMMIT thật:
 * - trigger DEFERRABLE `trg_bill_invariants` (BILL_PAYMENT_STATUS_MISMATCH, VOID_NOT_COMPLETE) chỉ báo lỗi lúc COMMIT;
 * - trigger ngay lập tức giới hạn thu/hoàn tiền có khóa dòng Bill (PAYMENT_EXCEEDS_REMAINING, REFUND_EXCEEDS_REFUNDABLE);
 * - mã chứng từ do trigger cấp và được đọc lại vào entity (@Generated).
 * Tương ứng PAY-01..PAY-14, RF-01..RF-12, VOID-04 của tools/schema-test/test_schema.py.
 */
class PaymentAndRefundTriggerIT : IntegrationTestBase() {

    private var staffId = 0L
    private var managerId = 0L

    @BeforeEach
    fun setUp() {
        staffId = fx.user("cashier", UserRole.STAFF)
        managerId = fx.user("manager", UserRole.MANAGER)
    }

    private fun today(): String = LocalDate.now(BusinessTime.ZONE).format(DateTimeFormatter.BASIC_ISO_DATE)

    /** Phiên T02 có 1 món CONFIRMED [total], đã Tính tiền (Bill ACTIVE UNPAID). */
    private fun billOf(total: Long, table: String = "T02"): Pair<Long, Long> {
        val (sessionId, orderId) = fx.openSessionWithOrder(table)
        fx.addConfirmedItem(orderId, fx.addBatch(sessionId, orderId, 1), total)
        return fx.lockAndBill(sessionId, orderId, total, staffId) to orderId
    }

    private fun payment(billId: Long, amount: Long) =
        PaymentTransaction(billId, UUID.randomUUID(), PaymentMethod.CASH, amount, TransactionStatus.SUCCESS, staffId).apply {
            confirmedBy = staffId
            confirmedAt = OffsetDateTime.now()
        }

    @Test
    fun `bill created through JPA gets BILL-YYYYMMDD-NNNN from trigger`() {
        val (sessionId, orderId) = fx.openSessionWithOrder("T03")
        fx.addConfirmedItem(orderId, fx.addBatch(sessionId, orderId, 1), 215_000)
        tx.executeWithoutResult {
            jdbc.update("UPDATE table_session SET status = 'BILLING' WHERE id = ?", sessionId)
            jdbc.update("UPDATE orders SET status = 'LOCKED' WHERE id = ?", orderId)
        }

        val bill = tx.execute {
            Bill(
                orderId = orderId, subtotalAmount = 215_000, discountAmount = 0, netAmount = 215_000,
                serviceFeeRate = BigDecimal.ZERO, serviceFeeAmount = 0, totalAmount = 215_000,
                vatRate = BigDecimal("0.0800"), vatAmount = fx.vatOf(215_000), createdBy = staffId,
            ).also { em.persist(it); em.flush() }
        }!!

        assertEquals("BILL-${today()}-0001", bill.billNumber)
        assertEquals(BillStatus.ACTIVE, bill.status)
    }

    @Test
    fun `payment with matching payment_status commits, receipt number is generated, history is written`() {
        val (billId, _) = billOf(215_000)

        val payment = tx.execute {
            val p = payment(billId, 100_000)
            em.persist(p)
            em.find(Bill::class.java, billId).paymentStatus = PaymentStatus.PARTIALLY_PAID
            p
        }!!

        assertEquals("${today()}-0001", payment.receiptNumber)
        val history = jdbc.queryForList(
            "SELECT from_status, to_status FROM transaction_status_history WHERE payment_transaction_id = ?", payment.id,
        )
        assertEquals(1, history.size)
        assertEquals("SUCCESS", history.single()["to_status"])
        assertEquals(1, em.createQuery("select h from TransactionStatusHistory h", TransactionStatusHistory::class.java).resultList.size)
    }

    @Test
    fun `payment_status left stale fails at COMMIT with BILL_PAYMENT_STATUS_MISMATCH (deferred trigger)`() {
        val (billId, _) = billOf(215_000)

        val error = expectDbError(ErrorCode.BILL_PAYMENT_STATUS_MISMATCH) {
            tx.executeWithoutResult { em.persist(payment(billId, 100_000)) } // quên cập nhật payment_status
        }

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.errorCode.httpStatus)
        assertEquals(0L, fx.count("SELECT count(*) FROM payment_transaction WHERE bill_id = ?", billId), "Phải rollback")
    }

    @Test
    fun `payment exceeding the remaining amount is rejected immediately (PAYMENT_EXCEEDS_REMAINING 422)`() {
        val (billId, _) = billOf(215_000)
        fx.pay(billId, 100_000, "PARTIALLY_PAID", staffId)

        val error = expectDbError(ErrorCode.PAYMENT_EXCEEDS_REMAINING) {
            tx.executeWithoutResult {
                em.persist(payment(billId, 115_001))
                em.find(Bill::class.java, billId).apply { paymentStatus = PaymentStatus.PAID; paidAt = OffsetDateTime.now() }
            }
        }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.errorCode.httpStatus)
    }

    @Test
    fun `two cashiers paying 60k on a 100k bill at the same time - only one succeeds`() {
        val (billId, _) = billOf(100_000, table = "T06")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit(Callable {
                    start.await()
                    runCatching {
                        tx.executeWithoutResult {
                            em.persist(payment(billId, 60_000))
                            em.find(Bill::class.java, billId).paymentStatus = PaymentStatus.PARTIALLY_PAID
                        }
                    }.exceptionOrNull()?.let { dbErrorTranslator.translate(it)?.errorCode ?: it }
                })
            }
            start.countDown()
            val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, outcomes.count { it === null }, "Kết quả: $outcomes")
            assertEquals(1, outcomes.count { (it as? ErrorCode) == ErrorCode.PAYMENT_EXCEEDS_REMAINING }, "Kết quả: $outcomes")
            assertEquals(
                60_000L,
                fx.count("SELECT COALESCE(sum(amount), 0) FROM payment_transaction WHERE bill_id = ? AND status = 'SUCCESS'", billId),
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `refund on an ACTIVE bill is rejected (REFUND_REQUIRES_VOID_PENDING)`() {
        val (billId, _) = billOf(80_000)
        fx.pay(billId, 80_000, "PAID", staffId)

        expectDbError(ErrorCode.REFUND_REQUIRES_VOID_PENDING) {
            tx.executeWithoutResult {
                em.persist(RefundTransaction(billId, UUID.randomUUID(), PaymentMethod.CASH, 1_000, TransactionStatus.PENDING, "Hoàn", managerId))
            }
        }
    }

    @Test
    fun `SRS 12_1 case 4 - TPS 500k, TRS 200k, TRP 200k - only 100k more can be refunded`() {
        val (billId, orderId) = billOf(500_000, table = "T07")
        fx.pay(billId, 500_000, "PAID", staffId)
        fx.requestVoid(billId, orderId, managerId, orderStatusBefore = "LOCKED")
        fx.refund(billId, 200_000, "SUCCESS", managerId, newPaymentStatus = "PARTIALLY_PAID")
        fx.refund(billId, 200_000, "PENDING", managerId)

        fun refund(amount: Long) = tx.executeWithoutResult {
            em.persist(RefundTransaction(billId, UUID.randomUUID(), PaymentMethod.CASH, amount, TransactionStatus.PENDING, "Hoàn tiếp", managerId))
        }

        val error = expectDbError(ErrorCode.REFUND_EXCEEDS_REFUNDABLE) { refund(100_001) }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.errorCode.httpStatus)
        refund(100_000)
        assertEquals(2L, fx.count("SELECT count(*) FROM refund_transaction WHERE bill_id = ? AND status = 'PENDING'", billId))
    }

    @Test
    fun `completing a void while a refund is still PENDING fails at COMMIT (VOID_NOT_COMPLETE)`() {
        val (billId, orderId) = billOf(215_000)
        fx.pay(billId, 215_000, "PAID", staffId)
        fx.requestVoid(billId, orderId, managerId, orderStatusBefore = "LOCKED")
        fx.refund(billId, 215_000, "PENDING", managerId)

        expectDbError(ErrorCode.VOID_NOT_COMPLETE) {
            tx.executeWithoutResult {
                em.find(Bill::class.java, billId).apply {
                    status = BillStatus.VOIDED
                    voidType = VoidType.MANAGER_VOID
                    voidedBy = managerId
                    voidedAt = OffsetDateTime.now()
                }
            }
        }
        assertEquals("VOID_PENDING", jdbc.queryForObject("SELECT status FROM bill WHERE id = ?", String::class.java, billId))
    }

    @Test
    fun `money transactions are immutable once confirmed (TRANSACTION_IMMUTABLE)`() {
        val (billId, _) = billOf(100_000)
        fx.pay(billId, 40_000, "PARTIALLY_PAID", staffId)

        expectDbError(ErrorCode.TRANSACTION_IMMUTABLE) {
            jdbc.update("UPDATE payment_transaction SET status = 'FAILED', failure_reason = 'x' WHERE bill_id = ?", billId)
        }
        assertTrue(fx.count("SELECT count(*) FROM payment_transaction WHERE bill_id = ? AND status = 'SUCCESS'", billId) == 1L)
    }
}
