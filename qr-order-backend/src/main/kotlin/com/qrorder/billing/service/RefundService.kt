package com.qrorder.billing.service

import com.qrorder.auth.repository.UserRepository
import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.domain.RefundTransaction
import com.qrorder.billing.domain.TransactionStatus
import com.qrorder.billing.dto.ConfirmRefundRequest
import com.qrorder.billing.dto.PaymentConfirmResult
import com.qrorder.billing.dto.PaymentEntryStatus
import com.qrorder.billing.dto.RecordRefundRequest
import com.qrorder.billing.dto.ReceiptHeader
import com.qrorder.billing.dto.RefundDetailResponse
import com.qrorder.billing.dto.RefundReceiptResponse
import com.qrorder.billing.dto.RefundResponse
import com.qrorder.billing.dto.RefundResultResponse
import com.qrorder.billing.dto.TransactionHistoryResponse
import com.qrorder.billing.event.BillChangeKind
import com.qrorder.billing.repository.BillRepository
import com.qrorder.billing.repository.PaymentTransactionRepository
import com.qrorder.billing.repository.RefundTransactionRepository
import com.qrorder.billing.repository.TransactionStatusHistoryRepository
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.common.time.DbClock
import com.qrorder.order.repository.OrderRepository
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.TableSessionRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Hoàn tiền cho Void (GĐ5b - BR-P13, O1, FR-PAY-08; SRS §6.12, §12.1). Chỉ Manager hoặc người có REFUND_RECORD.
 *
 * - RefundTransaction riêng, bất biến, idempotent theo `requestId`; chỉ tạo cho Bill VOID_PENDING.
 * - Mỗi Refund mới (PENDING hoặc SUCCESS) không vượt remaining_refundable = TPS - TRS - TRP, kiểm tra SAU khi khóa
 *   bàn -> phiên; trigger `fn_money_txn_validate` khóa dòng Bill và kiểm tra TRS + TRP <= TPS (hàng rào cuối).
 * - SUCCESS chỉ khi tiền thực tế đã hoàn: mã phiếu RF-YYYYMMDD-NNNN do trigger cấp; FAILED cần lý do, không tính vào
 *   TRS/TRP (Manager tạo Refund mới nếu vẫn cần hoàn).
 * - Sau mỗi SUCCESS: payment_status theo thu ròng TPS - TRS (BR-P11, cùng transaction); hoàn đủ (TRS = TPS, không
 *   còn Refund PENDING) thì Void hoàn tất: Bill VOIDED, Order LOCKED + needs_attention (BR-P17). Không tự chuyển tiền
 *   sang Bill thay thế.
 */
@Service
class RefundService(
    private val billingLocker: BillingLocker,
    private val billRepository: BillRepository,
    private val refundRepository: RefundTransactionRepository,
    private val paymentRepository: PaymentTransactionRepository,
    private val historyRepository: TransactionStatusHistoryRepository,
    private val orderRepository: OrderRepository,
    private val sessionRepository: TableSessionRepository,
    private val tableRepository: DiningTableRepository,
    private val userRepository: UserRepository,
    private val mapper: BillViewMapper,
    private val receiptProperties: ReceiptProperties,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun record(actorUserId: Long, billId: Long, request: RecordRefundRequest): RefundResultResponse {
        val requestId = requireNotNull(request.requestId)
        refundRepository.findByRequestId(requestId)?.let { return replay(it, billId, request) }
        val ctx = billingLocker.lockBill(billId)
        refundRepository.findByRequestId(requestId)?.let { return replay(it, billId, request) }

        val bill = ctx.bill
        if (bill.status != BillStatus.VOID_PENDING) {
            throw BusinessException(ErrorCode.REFUND_REQUIRES_VOID_PENDING, "Bill ${bill.billNumber} đang ${bill.status}; chỉ hoàn tiền cho Bill đang chờ Void")
        }
        val amount = requireNotNull(request.amount)
        val money = mapper.money(billId)
        if (amount > money.remainingRefundable) {
            throw BusinessException(
                ErrorCode.REFUND_EXCEEDS_REFUNDABLE,
                "Số tiền hoàn $amount vượt số còn được hoàn ${money.remainingRefundable} (TPS ${money.tps} - TRS ${money.trs} - TRP ${money.trp})",
            )
        }
        val success = request.status == PaymentEntryStatus.SUCCESS
        val now = dbClock.now()
        val reason = requireNotNull(request.reason).trim()
        val refund = RefundTransaction(
            billId = billId, requestId = requestId, method = requireNotNull(request.method), amount = amount,
            status = if (success) TransactionStatus.SUCCESS else TransactionStatus.PENDING, reason = reason, createdBy = actorUserId,
        ).apply {
            if (success) {
                confirmedBy = actorUserId
                confirmedAt = now
            }
        }
        val saved = refundRepository.saveAndFlush(refund)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = BillingAuditActions.REFUND_CREATED, entityType = BillingAuditEntityTypes.REFUND_TRANSACTION, entityId = saved.id,
                newValue = mapOf(
                    "billId" to billId, "billNumber" to bill.billNumber, "method" to saved.method, "amount" to amount,
                    "status" to saved.status, "receiptNumber" to saved.receiptNumber, "requestId" to requestId,
                ),
                reason = reason,
            ),
        )
        val completed = if (success) applySuccess(ctx, actorUserId, now) else {
            events.publishEvent(mapper.event(BillChangeKind.REFUND_PENDING, ctx.locked, bill))
            false
        }
        return result(saved, ctx, completed, replayed = false)
    }

    @Transactional
    fun confirm(actorUserId: Long, refundId: Long, request: ConfirmRefundRequest): RefundResultResponse {
        val billId = refundRepository.findBillIdById(refundId) ?: throw ResourceNotFoundException("giao dịch hoàn tiền", refundId)
        val ctx = billingLocker.lockBill(billId)
        val refund = refundRepository.findById(refundId).orElseThrow { ResourceNotFoundException("giao dịch hoàn tiền", refundId) }
        val target = if (request.result == PaymentConfirmResult.SUCCESS) TransactionStatus.SUCCESS else TransactionStatus.FAILED
        if (refund.status != TransactionStatus.PENDING) {
            if (refund.status == target) return result(refund, ctx, completed = false, replayed = true)
            throw BusinessException(ErrorCode.TRANSACTION_IMMUTABLE, "Giao dịch hoàn tiền đã ${refund.status}, không thể chuyển sang $target")
        }
        if (target == TransactionStatus.FAILED) {
            refund.failureReason = request.failureReason?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw BusinessException(ErrorCode.VALIDATION_FAILED, "Lý do thất bại là bắt buộc khi hoàn tiền FAILED")
        }
        val now = dbClock.now()
        refund.status = target
        refund.confirmedBy = actorUserId
        refund.confirmedAt = now
        val saved = refundRepository.saveAndFlush(refund)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = if (target == TransactionStatus.SUCCESS) BillingAuditActions.REFUND_CONFIRMED else BillingAuditActions.REFUND_FAILED,
                entityType = BillingAuditEntityTypes.REFUND_TRANSACTION, entityId = refundId,
                oldValue = mapOf("status" to TransactionStatus.PENDING),
                newValue = mapOf("status" to target, "amount" to saved.amount, "receiptNumber" to saved.receiptNumber, "billId" to billId),
                reason = saved.failureReason,
            ),
        )
        val completed = if (target == TransactionStatus.SUCCESS) applySuccess(ctx, actorUserId, now) else {
            events.publishEvent(mapper.event(BillChangeKind.REFUND_FAILED, ctx.locked, ctx.bill))
            false
        }
        return result(saved, ctx, completed, replayed = false)
    }

    @Transactional(readOnly = true)
    fun listByBill(billId: Long): List<RefundResponse> {
        if (!billRepository.existsById(billId)) throw ResourceNotFoundException("Bill", billId)
        return refundRepository.findAllByBillIdOrderByIdAsc(billId).map(mapper::refund)
    }

    @Transactional(readOnly = true)
    fun detail(refundId: Long): RefundDetailResponse {
        val refund = refundRepository.findById(refundId).orElseThrow { ResourceNotFoundException("giao dịch hoàn tiền", refundId) }
        val history = historyRepository.findAllByRefundTransactionIdOrderByIdAsc(refundId).map {
            TransactionHistoryResponse(it.fromStatus, it.toStatus, it.changedBy, it.changedAt, it.reason)
        }
        return RefundDetailResponse(mapper.refund(refund), history)
    }

    /** Dữ liệu in phiếu hoàn tiền nội bộ RF-YYYYMMDD-NNNN (FR-PAY-12); chỉ Refund SUCCESS. */
    @Transactional(readOnly = true)
    fun receipt(refundId: Long): RefundReceiptResponse {
        val refund = refundRepository.findById(refundId).orElseThrow { ResourceNotFoundException("giao dịch hoàn tiền", refundId) }
        if (refund.status != TransactionStatus.SUCCESS) {
            throw BusinessException(ErrorCode.RECEIPT_NOT_AVAILABLE, "Giao dịch hoàn tiền đang ${refund.status}, chưa có phiếu")
        }
        val bill = billRepository.findById(refund.billId).orElseThrow { ResourceNotFoundException("Bill", refund.billId) }
        val order = orderRepository.findById(bill.orderId).orElseThrow { ResourceNotFoundException("order", bill.orderId) }
        val session = sessionRepository.findById(order.sessionId).orElseThrow { ResourceNotFoundException("phiên bàn", order.sessionId) }
        val table = tableRepository.findById(session.tableId).orElseThrow { ResourceNotFoundException("bàn", session.tableId) }
        val byConfirmation = compareBy<RefundTransaction>({ it.confirmedAt ?: OffsetDateTime.MAX }, { it.id })
        val successes = refundRepository.findAllByBillIdOrderByIdAsc(requireNotNull(bill.id))
            .filter { it.status == TransactionStatus.SUCCESS }.sortedWith(byConfirmation)
        val refundedBefore = successes.takeWhile { it.id != refund.id }.sumOf { it.amount }
        val refundedAfter = refundedBefore + refund.amount
        val tps = paymentRepository.findAllByBillIdOrderByIdAsc(requireNotNull(bill.id))
            .filter { it.status == TransactionStatus.SUCCESS }.sumOf { it.amount }
        val managerId = refund.confirmedBy ?: refund.createdBy
        return RefundReceiptResponse(
            header = ReceiptHeader(
                restaurantName = receiptProperties.restaurantName,
                address = receiptProperties.address.takeIf { it.isNotBlank() },
                phone = receiptProperties.phone.takeIf { it.isNotBlank() },
                taxCode = receiptProperties.taxCode.takeIf { it.isNotBlank() },
            ),
            receiptNumber = requireNotNull(refund.receiptNumber), issuedAt = refund.confirmedAt,
            billNumber = requireNotNull(bill.billNumber), billStatus = bill.status, tableCode = table.code, tableName = table.displayName,
            managerName = userRepository.findById(managerId).map { it.fullName }.orElse(null),
            method = refund.method, amount = refund.amount, reason = refund.reason, voidReason = bill.voidReason,
            billTotalAmount = bill.totalAmount, totalPaidSuccess = tps, refundedBefore = refundedBefore, refundedAfter = refundedAfter,
            remainingToRefund = maxOf(0L, tps - refundedAfter), footer = receiptProperties.footer,
        )
    }

    // =========================================================================================== nội bộ

    /** Sau một Refund SUCCESS: cập nhật payment_status (BR-P11); hoàn đủ thì hoàn tất Void. Trả true nếu Void vừa hoàn tất. */
    private fun applySuccess(ctx: BillContext, actorUserId: Long, now: OffsetDateTime): Boolean {
        val bill = ctx.bill
        val money = mapper.money(ctx.billId)
        bill.paymentStatus = money.paymentStatus(bill.totalAmount)
        val completed = money.fullyRefunded
        if (completed) VoidService.completeVoid(bill, ctx.order, actorUserId, now)
        billRepository.saveAndFlush(bill)
        if (!completed) {
            events.publishEvent(mapper.event(BillChangeKind.REFUND_SUCCEEDED, ctx.locked, bill, money))
            return false
        }
        orderRepository.saveAndFlush(ctx.order)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = BillingAuditActions.BILL_VOIDED, entityType = BillingAuditEntityTypes.BILL, entityId = ctx.billId,
                oldValue = mapOf("status" to BillStatus.VOID_PENDING),
                newValue = mapOf(
                    "status" to BillStatus.VOIDED, "refundedAmount" to money.trs, "totalPaidSuccess" to money.tps,
                    "orderNeedsAttention" to true,
                ),
                reason = bill.voidReason,
            ),
        )
        events.publishEvent(mapper.event(BillChangeKind.BILL_VOIDED, ctx.locked, bill, money))
        return true
    }

    private fun replay(existing: RefundTransaction, billId: Long, request: RecordRefundRequest): RefundResultResponse {
        if (existing.billId != billId || existing.amount != request.amount || existing.method != request.method) {
            throw BusinessException(ErrorCode.DUPLICATE_REQUEST_ID, "requestId đã dùng cho một giao dịch hoàn tiền khác")
        }
        val bill = billRepository.findById(billId).orElseThrow { ResourceNotFoundException("Bill", billId) }
        val order = orderRepository.findById(bill.orderId).orElseThrow { ResourceNotFoundException("order", bill.orderId) }
        return RefundResultResponse(
            mapper.refund(existing), mapper.refundSummary(bill), order.status, order.needsAttention, voidCompleted = false, replayed = true,
        )
    }

    private fun result(refund: RefundTransaction, ctx: BillContext, completed: Boolean, replayed: Boolean) = RefundResultResponse(
        refund = mapper.refund(refund), summary = mapper.refundSummary(ctx.bill), orderStatus = ctx.order.status,
        orderNeedsAttention = ctx.order.needsAttention, voidCompleted = completed, replayed = replayed,
    )
}
