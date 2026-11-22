package com.qrorder.billing.service

import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.domain.PaymentMethod
import com.qrorder.billing.domain.PaymentStatus
import com.qrorder.billing.domain.PaymentTransaction
import com.qrorder.billing.domain.TransactionStatus
import com.qrorder.billing.dto.ConfirmPaymentRequest
import com.qrorder.billing.dto.PaymentConfirmResult
import com.qrorder.billing.dto.PaymentDetailResponse
import com.qrorder.billing.dto.PaymentEntryStatus
import com.qrorder.billing.dto.PaymentResponse
import com.qrorder.billing.dto.PaymentResultResponse
import com.qrorder.billing.dto.RecordPaymentRequest
import com.qrorder.billing.dto.TransactionHistoryResponse
import com.qrorder.billing.event.BillChangeKind
import com.qrorder.billing.repository.BillRepository
import com.qrorder.billing.repository.PaymentTransactionRepository
import com.qrorder.billing.repository.TransactionStatusHistoryRepository
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.common.time.DbClock
import com.qrorder.order.domain.OrderStatus
import com.qrorder.order.repository.OrderRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Ghi nhận thanh toán (FR-PAY-04, BR-P10..P12, DEC-11 C). Nhân viên có PAYMENT_PROCESS (Manager mặc định có).
 *
 * - Thu nhiều lần (chia tiền): mỗi lần là một PaymentTransaction bất biến, idempotent theo `requestId` (gửi lại cùng
 *   requestId và cùng nội dung -> trả lại giao dịch cũ, 200; khác Bill/số tiền/phương thức -> 409 DUPLICATE_REQUEST_ID).
 * - Trạng thái khi ghi: SUCCESS (đã thu) hoặc PENDING (chờ quẹt thẻ/đối soát); PENDING -> SUCCESS/FAILED đúng một lần.
 * - Không thu vượt: amount <= Total - (TPS + TPP - TRS) kiểm tra ở service SAU khi khóa bàn -> phiên (tuần tự hóa các
 *   lần thu đồng thời của cùng bàn); trigger `fn_money_txn_validate` khóa dòng Bill và kiểm tra lại (hàng rào cuối).
 * - Mã phiếu YYYYMMDD-NNNN do trigger cấp khi SUCCESS (BR-P12), đọc lại sau flush (@Generated).
 * - payment_status cập nhật trong CÙNG transaction (trg_bill_invariants kiểm tra lúc COMMIT). Thu đủ: Bill PAID,
 *   Order LOCKED -> PAID, rồi tự đóng phiên nếu món đã xong ([SessionSettlementService]).
 */
@Service
class PaymentService(
    private val billingLocker: BillingLocker,
    private val billRepository: BillRepository,
    private val paymentRepository: PaymentTransactionRepository,
    private val historyRepository: TransactionStatusHistoryRepository,
    private val orderRepository: OrderRepository,
    private val settlement: SessionSettlementService,
    private val mapper: BillViewMapper,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
) {

    @Transactional
    fun record(actorUserId: Long, billId: Long, request: RecordPaymentRequest): PaymentResultResponse {
        val requestId = requireNotNull(request.requestId)
        paymentRepository.findByRequestId(requestId)?.let { return replay(it, billId, request) }

        val ctx = billingLocker.lockBill(billId)
        // Hai request cùng requestId chạy song song: request sau chờ khóa bàn rồi thấy giao dịch của request trước.
        paymentRepository.findByRequestId(requestId)?.let { return replay(it, billId, request) }

        val bill = ctx.bill
        if (bill.status != BillStatus.ACTIVE) {
            throw BusinessException(ErrorCode.PAYMENT_BILL_NOT_ACTIVE, "Bill ${bill.billNumber} đang ${bill.status}, không nhận thanh toán")
        }
        val amount = requireNotNull(request.amount)
        val remaining = mapper.money(billId).remaining(bill.totalAmount)
        if (amount > remaining) {
            throw BusinessException(
                ErrorCode.PAYMENT_EXCEEDS_REMAINING,
                "Số tiền $amount vượt số còn có thể thu $remaining của Bill ${bill.billNumber} (đã tính cả giao dịch đang chờ)",
            )
        }
        val method = requireNotNull(request.method)
        val success = request.status == PaymentEntryStatus.SUCCESS
        val now = dbClock.now()
        val payment = PaymentTransaction(
            billId = billId, requestId = requestId, method = method, amount = amount,
            status = if (success) TransactionStatus.SUCCESS else TransactionStatus.PENDING, createdBy = actorUserId,
        ).apply {
            note = request.note?.trim()?.takeIf { it.isNotEmpty() }
            applyProvider(this, method, request.provider, request.providerTxnId)
            if (success) {
                confirmedBy = actorUserId
                confirmedAt = now
            }
        }
        val saved = paymentRepository.saveAndFlush(payment)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = BillingAuditActions.PAYMENT_RECORDED, entityType = BillingAuditEntityTypes.PAYMENT_TRANSACTION, entityId = saved.id,
                newValue = mapOf(
                    "billId" to billId, "billNumber" to bill.billNumber, "method" to method, "amount" to amount,
                    "status" to saved.status, "receiptNumber" to saved.receiptNumber, "requestId" to requestId,
                ),
            ),
        )
        val closed = if (success) applySuccess(ctx, actorUserId, now) else {
            events.publishEvent(mapper.event(BillChangeKind.PAYMENT_PENDING, ctx.locked, bill))
            false
        }
        return result(saved, ctx, closed, replayed = false)
    }

    /** PENDING -> SUCCESS (cấp mã phiếu) hoặc FAILED (bắt buộc lý do). Xác nhận lại cùng kết quả: trả lại kết quả cũ. */
    @Transactional
    fun confirm(actorUserId: Long, paymentId: Long, request: ConfirmPaymentRequest): PaymentResultResponse {
        val billId = paymentRepository.findBillIdById(paymentId) ?: throw ResourceNotFoundException("giao dịch thanh toán", paymentId)
        val ctx = billingLocker.lockBill(billId)
        val payment = paymentRepository.findById(paymentId).orElseThrow { ResourceNotFoundException("giao dịch thanh toán", paymentId) }
        val target = if (request.result == PaymentConfirmResult.SUCCESS) TransactionStatus.SUCCESS else TransactionStatus.FAILED
        if (payment.status != TransactionStatus.PENDING) {
            if (payment.status == target) return result(payment, ctx, closed = false, replayed = true)
            throw BusinessException(ErrorCode.TRANSACTION_IMMUTABLE, "Giao dịch đã ${payment.status}, không thể chuyển sang $target")
        }
        val now = dbClock.now()
        if (target == TransactionStatus.SUCCESS) {
            if (ctx.bill.status != BillStatus.ACTIVE) {
                throw BusinessException(ErrorCode.PAYMENT_BILL_NOT_ACTIVE, "Bill ${ctx.bill.billNumber} đang ${ctx.bill.status}")
            }
            applyProvider(payment, payment.method, request.provider, request.providerTxnId)
        } else {
            payment.failureReason = request.failureReason?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw BusinessException(ErrorCode.VALIDATION_FAILED, "Lý do thất bại là bắt buộc khi giao dịch FAILED")
        }
        payment.status = target
        payment.confirmedBy = actorUserId
        payment.confirmedAt = now
        val saved = paymentRepository.saveAndFlush(payment)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = if (target == TransactionStatus.SUCCESS) BillingAuditActions.PAYMENT_CONFIRMED else BillingAuditActions.PAYMENT_FAILED,
                entityType = BillingAuditEntityTypes.PAYMENT_TRANSACTION, entityId = paymentId,
                oldValue = mapOf("status" to TransactionStatus.PENDING),
                newValue = mapOf("status" to target, "amount" to saved.amount, "receiptNumber" to saved.receiptNumber, "billId" to billId),
                reason = saved.failureReason,
            ),
        )
        val closed = if (target == TransactionStatus.SUCCESS) applySuccess(ctx, actorUserId, now) else {
            events.publishEvent(mapper.event(BillChangeKind.PAYMENT_FAILED, ctx.locked, ctx.bill))
            false
        }
        return result(saved, ctx, closed, replayed = false)
    }

    @Transactional(readOnly = true)
    fun listByBill(billId: Long): List<PaymentResponse> {
        if (!billRepository.existsById(billId)) throw ResourceNotFoundException("Bill", billId)
        return paymentRepository.findAllByBillIdOrderByIdAsc(billId).map(mapper::payment)
    }

    @Transactional(readOnly = true)
    fun detail(paymentId: Long): PaymentDetailResponse {
        val payment = paymentRepository.findById(paymentId).orElseThrow { ResourceNotFoundException("giao dịch thanh toán", paymentId) }
        val history = historyRepository.findAllByPaymentTransactionIdOrderByIdAsc(paymentId).map {
            TransactionHistoryResponse(it.fromStatus, it.toStatus, it.changedBy, it.changedAt, it.reason)
        }
        return PaymentDetailResponse(mapper.payment(payment), history)
    }

    // =========================================================================================== nội bộ

    /**
     * Sau một giao dịch SUCCESS: cập nhật payment_status theo tiền thu ròng (BR-P11); PAID thì Order PAID và thử đóng phiên.
     * Trả true nếu phiên vừa đóng.
     */
    private fun applySuccess(ctx: BillContext, actorUserId: Long, now: OffsetDateTime): Boolean {
        val bill = ctx.bill
        val money = mapper.money(ctx.billId)
        val status = money.paymentStatus(bill.totalAmount)
        if (status != bill.paymentStatus) {
            bill.paymentStatus = status
            if (status == PaymentStatus.PAID) bill.paidAt = now
            billRepository.saveAndFlush(bill)
        }
        if (status != PaymentStatus.PAID) {
            events.publishEvent(mapper.event(BillChangeKind.PAYMENT_SUCCEEDED, ctx.locked, bill, money))
            return false
        }
        val order = ctx.order
        if (order.status == OrderStatus.LOCKED) {
            order.status = OrderStatus.PAID
            order.paidAt = now
            orderRepository.saveAndFlush(order)
        }
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = BillingAuditActions.BILL_PAID, entityType = BillingAuditEntityTypes.BILL, entityId = ctx.billId,
                newValue = mapOf("paymentStatus" to PaymentStatus.PAID, "netCollected" to money.netCollected, "orderStatus" to order.status),
            ),
        )
        events.publishEvent(mapper.event(BillChangeKind.BILL_PAID, ctx.locked, bill, money))
        return settlement.closeIfSettled(ctx.locked, order, actorUserId)
    }

    /**
     * provider_txn_id cần provider (CHECK ck_payment_transaction_provider). Thanh toán thủ công không có cổng nên
     * provider mặc định = tên phương thức (ví dụ BANK_TRANSFER + mã tham chiếu ngân hàng); trùng cặp -> 409 DUPLICATE_PROVIDER_TXN.
     */
    private fun applyProvider(payment: PaymentTransaction, method: PaymentMethod, provider: String?, providerTxnId: String?) {
        val txn = providerTxnId?.trim()?.takeIf { it.isNotEmpty() }
        val name = provider?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null) payment.provider = name
        if (txn != null) {
            payment.providerTxnId = txn
            if (payment.provider == null) payment.provider = method.name
        }
    }

    private fun replay(existing: PaymentTransaction, billId: Long, request: RecordPaymentRequest): PaymentResultResponse {
        if (existing.billId != billId || existing.amount != request.amount || existing.method != request.method) {
            throw BusinessException(ErrorCode.DUPLICATE_REQUEST_ID, "requestId đã dùng cho một giao dịch khác")
        }
        val bill = billRepository.findById(billId).orElseThrow { ResourceNotFoundException("Bill", billId) }
        val order = orderRepository.findById(bill.orderId).orElseThrow { ResourceNotFoundException("order", bill.orderId) }
        val sessionStatus = billingLocker.sessionStatusOf(order.sessionId)
        return PaymentResultResponse(mapper.payment(existing), mapper.summary(bill), order.status, sessionStatus, sessionClosed = false, replayed = true)
    }

    private fun result(payment: PaymentTransaction, ctx: BillContext, closed: Boolean, replayed: Boolean) = PaymentResultResponse(
        payment = mapper.payment(payment), bill = mapper.summary(ctx.bill), orderStatus = ctx.order.status,
        sessionStatus = ctx.locked.session.status, sessionClosed = closed, replayed = replayed,
    )
}
