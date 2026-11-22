package com.qrorder.billing.repository

import com.qrorder.billing.domain.Bill
import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.domain.PaymentTransaction
import com.qrorder.billing.domain.RefundTransaction
import com.qrorder.billing.domain.TransactionStatusHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface BillRepository : JpaRepository<Bill, Long> {
    /** Bill hiện hành (ACTIVE hoặc VOID_PENDING) của Order - tối đa một (ux_bill_current_per_order, BR-P06). */
    fun findFirstByOrderIdAndStatusIn(orderId: Long, statuses: Collection<BillStatus>): Bill?

    fun findAllByOrderIdOrderByIdAsc(orderId: Long): List<Bill>

    /** Order của Bill, đọc dạng giá trị vô hướng để khóa bàn/phiên TRƯỚC rồi mới nạp entity. */
    @Query("select b.orderId from Bill b where b.id = :id")
    fun findOrderIdById(@Param("id") id: Long): Long?

    /** Danh sách "Cần xử lý" (ix_bill_void_pending): Bill đang chờ hoàn tiền, cũ trước. */
    fun findAllByStatusOrderByVoidRequestedAtAscIdAsc(status: BillStatus): List<Bill>
}

interface PaymentTransactionRepository : JpaRepository<PaymentTransaction, Long> {
    /** Idempotency (NFR-06): requestId là khóa duy nhất toàn hệ thống (uq_payment_transaction_request). */
    fun findByRequestId(requestId: UUID): PaymentTransaction?

    fun findAllByBillIdOrderByIdAsc(billId: Long): List<PaymentTransaction>

    @Query("select p.billId from PaymentTransaction p where p.id = :id")
    fun findBillIdById(@Param("id") id: Long): Long?
}

interface RefundTransactionRepository : JpaRepository<RefundTransaction, Long> {
    fun findAllByBillIdOrderByIdAsc(billId: Long): List<RefundTransaction>

    /** Idempotency (BR-P13): requestId duy nhất toàn hệ thống (uq_refund_transaction_request). */
    fun findByRequestId(requestId: UUID): RefundTransaction?

    @Query("select r.billId from RefundTransaction r where r.id = :id")
    fun findBillIdById(@Param("id") id: Long): Long?
}

interface TransactionStatusHistoryRepository : JpaRepository<TransactionStatusHistory, Long> {
    fun findAllByPaymentTransactionIdOrderByIdAsc(paymentTransactionId: Long): List<TransactionStatusHistory>

    fun findAllByRefundTransactionIdOrderByIdAsc(refundTransactionId: Long): List<TransactionStatusHistory>
}
