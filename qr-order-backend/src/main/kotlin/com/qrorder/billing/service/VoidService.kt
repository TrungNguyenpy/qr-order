package com.qrorder.billing.service

import com.qrorder.billing.domain.Bill
import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.domain.PaymentStatus
import com.qrorder.billing.domain.TransactionStatus
import com.qrorder.billing.domain.VoidType
import com.qrorder.billing.dto.AttentionType
import com.qrorder.billing.dto.BillingAttentionItem
import com.qrorder.billing.dto.RequestVoidRequest
import com.qrorder.billing.dto.VoidResultResponse
import com.qrorder.billing.dto.WithdrawVoidRequest
import com.qrorder.billing.event.BillChangeKind
import com.qrorder.billing.repository.BillRepository
import com.qrorder.billing.repository.RefundTransactionRepository
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.time.DbClock
import com.qrorder.order.domain.Order
import com.qrorder.order.domain.OrderAttentionReason
import com.qrorder.order.domain.OrderStatus
import com.qrorder.order.repository.OrderRepository
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.TableSessionRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Void Bill có hoàn tiền và rút Void (GĐ5b - BR-P09, BR-P14, BR-P17, O1, P2, P-3; SRS §6.12, §6.13).
 * Chỉ Manager hoặc người được cấp BILL_VOID (kiểm tra ở controller). Áp dụng cả khi phiên đã CLOSED; phiên và bàn
 * không đổi trạng thái. Mọi thao tác khóa bàn -> phiên trước (cùng thứ tự với các module khác).
 *
 * Yêu cầu Void (Bill phải ACTIVE, không có Payment PENDING):
 * - Thu ròng (TPS - TRS) = 0: Bill VOIDED ngay (void_type MANAGER_VOID), Order về LOCKED (nếu đang PAID) và gắn
 *   needs_attention AWAITING_REPLACEMENT_BILL (BR-P17) - chờ Manager tạo Bill thay thế.
 * - Thu ròng > 0: Bill VOID_PENDING (chặn thu thêm, chặn Bill mới), Order lưu `status_before_void` rồi PAID -> LOCKED.
 *   Manager hoàn tiền bằng RefundTransaction ([RefundService]); hoàn đủ thì Bill VOIDED.
 *
 * Rút Void (Bill VOID_PENDING, không có Refund PENDING/SUCCESS; Refund FAILED không chặn): Bill ACTIVE, Order về
 * trạng thái trước Void; nếu về PAID mà món đã xong và phiên còn BILLING thì phiên tự đóng như GĐ5a.
 */
@Service
class VoidService(
    private val billingLocker: BillingLocker,
    private val billRepository: BillRepository,
    private val refundRepository: RefundTransactionRepository,
    private val orderRepository: OrderRepository,
    private val sessionRepository: TableSessionRepository,
    private val tableRepository: DiningTableRepository,
    private val settlement: SessionSettlementService,
    private val mapper: BillViewMapper,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun requestVoid(actorUserId: Long, billId: Long, request: RequestVoidRequest): VoidResultResponse {
        val reason = requireNotNull(request.reason).trim()
        val ctx = billingLocker.lockBill(billId)
        val bill = ctx.bill
        when (bill.status) {
            BillStatus.ACTIVE -> Unit
            BillStatus.VOID_PENDING -> throw BusinessException(ErrorCode.BILL_VOID_PENDING, "Bill ${bill.billNumber} đã đang chờ hoàn tiền")
            else -> throw BusinessException(ErrorCode.BILL_NOT_ACTIVE, "Bill ${bill.billNumber} đang ${bill.status}, không thể Void")
        }
        requireVersionIfGiven(request.version, bill.version)
        val order = ctx.order
        if (order.status != OrderStatus.LOCKED && order.status != OrderStatus.PAID) {
            throw BusinessException(ErrorCode.INVALID_ORDER_TRANSITION, "Order đang ${order.status}, không thể Void Bill")
        }
        val money = mapper.money(billId)
        if (money.tpp > 0) {
            throw BusinessException(ErrorCode.PENDING_PAYMENT_EXISTS, "Bill ${bill.billNumber} còn giao dịch thanh toán đang chờ, hãy xác nhận SUCCESS/FAILED trước khi Void")
        }
        val now = dbClock.now()
        val previousOrderStatus = order.status
        bill.voidRequestedBy = actorUserId
        bill.voidRequestedAt = now
        bill.voidReason = reason

        val completed = money.netCollected == 0L
        if (completed) {
            // BR-P09: không có tiền cần hoàn -> VOIDED ngay; Order chờ Bill thay thế (BR-P17).
            completeVoid(bill, order, actorUserId, now)
            order.paidAt = null
            billRepository.saveAndFlush(bill)
            orderRepository.saveAndFlush(order)
        } else {
            bill.status = BillStatus.VOID_PENDING
            billRepository.saveAndFlush(bill)
            order.statusBeforeVoid = previousOrderStatus
            order.status = OrderStatus.LOCKED
            orderRepository.saveAndFlush(order)
        }

        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = BillingAuditActions.BILL_VOID_REQUESTED, entityType = BillingAuditEntityTypes.BILL, entityId = billId,
                oldValue = mapOf("status" to BillStatus.ACTIVE, "orderStatus" to previousOrderStatus),
                newValue = mapOf(
                    "status" to bill.status, "orderStatus" to order.status, "netCollected" to money.netCollected,
                    "remainingRefundable" to money.remainingRefundable,
                ),
                reason = reason,
            ),
        )
        if (completed) {
            auditVoided(actorUserId, billId, BillStatus.ACTIVE, reason)
            events.publishEvent(mapper.event(BillChangeKind.BILL_VOIDED, ctx.locked, bill, money))
        } else {
            events.publishEvent(mapper.event(BillChangeKind.VOID_REQUESTED, ctx.locked, bill, money))
        }
        return VoidResultResponse(mapper.bill(bill, order, LockedSessionView.of(ctx.locked)), completed)
    }

    @Transactional
    fun withdrawVoid(actorUserId: Long, billId: Long, request: WithdrawVoidRequest): VoidResultResponse {
        val reason = requireNotNull(request.reason).trim()
        val ctx = billingLocker.lockBill(billId)
        val bill = ctx.bill
        if (bill.status != BillStatus.VOID_PENDING) {
            throw BusinessException(ErrorCode.BILL_NOT_VOID_PENDING, "Bill ${bill.billNumber} đang ${bill.status}, không có yêu cầu Void để rút")
        }
        val blocking = refundRepository.findAllByBillIdOrderByIdAsc(billId)
            .any { it.status == TransactionStatus.PENDING || it.status == TransactionStatus.SUCCESS }
        if (blocking) throw BusinessException(ErrorCode.VOID_HAS_REFUNDS)

        val now = dbClock.now()
        bill.status = BillStatus.ACTIVE
        bill.voidWithdrawnBy = actorUserId
        bill.voidWithdrawnAt = now
        bill.voidWithdrawReason = reason
        billRepository.saveAndFlush(bill)

        val order = ctx.order
        val restored = order.statusBeforeVoid ?: OrderStatus.LOCKED
        order.statusBeforeVoid = null
        if (restored == OrderStatus.PAID) {
            order.status = OrderStatus.PAID
            if (order.paidAt == null) order.paidAt = now
        }
        orderRepository.saveAndFlush(order)

        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = BillingAuditActions.BILL_VOID_WITHDRAWN, entityType = BillingAuditEntityTypes.BILL, entityId = billId,
                oldValue = mapOf("status" to BillStatus.VOID_PENDING, "orderStatus" to OrderStatus.LOCKED),
                newValue = mapOf("status" to BillStatus.ACTIVE, "orderStatus" to order.status),
                reason = reason,
            ),
        )
        events.publishEvent(mapper.event(BillChangeKind.VOID_WITHDRAWN, ctx.locked, bill))
        // Trong lúc chờ Void món có thể đã phục vụ xong: Order về PAID thì đóng phiên như GĐ5a (nếu phiên còn BILLING).
        if (order.status == OrderStatus.PAID) settlement.closeIfSettled(ctx.locked, order, actorUserId)
        return VoidResultResponse(mapper.bill(bill, order, LockedSessionView.of(ctx.locked)), false)
    }

    /** FR-PAY-13: Bill VOID_PENDING, Order chờ Bill thay thế và Bill chưa thu đủ của phiên đã đóng, cũ trước. */
    @Transactional(readOnly = true)
    fun attention(): List<BillingAttentionItem> {
        val pending = billRepository.findAllByStatusOrderByVoidRequestedAtAscIdAsc(BillStatus.VOID_PENDING)
        val awaiting = orderRepository.findAllByNeedsAttentionTrueOrderByAttentionSetAtAscIdAsc()
        val unpaid = billRepository.findUnpaidInClosedSessions(BillStatus.ACTIVE, PaymentStatus.PAID, SessionStatus.CLOSED)
        val orders = (
            orderRepository.findAllById((pending + unpaid).map { it.orderId }.distinct()) + awaiting
            ).associateBy { it.id }
        val sessions = sessionRepository.findAllById(orders.values.map { it.sessionId }.distinct()).associateBy { it.id }
        val tables = tableRepository.findAllById(sessions.values.map { it.tableId }.distinct()).associateBy { it.id }
        fun tableCode(order: Order?) = order?.let { sessions[it.sessionId] }?.let { tables[it.tableId] }?.code

        val voidItems = pending.map { b ->
            val order = orders[b.orderId]
            BillingAttentionItem(
                type = AttentionType.BILL_VOID_PENDING, orderId = b.orderId, sessionId = order?.sessionId ?: 0L,
                sessionStatus = order?.let { sessions[it.sessionId]?.status }, tableCode = tableCode(order),
                billId = b.id, billNumber = b.billNumber, since = b.voidRequestedAt,
                remainingRefundable = mapper.money(requireNotNull(b.id)).remainingRefundable, reason = b.voidReason,
            )
        }
        val replacementItems = awaiting.map { o ->
            val last = billRepository.findAllByOrderIdOrderByIdAsc(requireNotNull(o.id)).lastOrNull()
            BillingAttentionItem(
                type = AttentionType.AWAITING_REPLACEMENT_BILL, orderId = requireNotNull(o.id), sessionId = o.sessionId,
                sessionStatus = sessions[o.sessionId]?.status, tableCode = tableCode(o), billId = last?.id,
                billNumber = last?.billNumber, since = o.attentionSetAt, remainingRefundable = null, reason = last?.voidReason,
            )
        }
        val unpaidItems = unpaid.map { b ->
            val order = orders[b.orderId]
            BillingAttentionItem(
                type = AttentionType.BILL_AWAITING_PAYMENT, orderId = b.orderId, sessionId = order?.sessionId ?: 0L,
                sessionStatus = order?.let { sessions[it.sessionId]?.status }, tableCode = tableCode(order),
                billId = b.id, billNumber = b.billNumber, since = b.createdAt, remainingRefundable = null, reason = null,
                remainingAmount = mapper.money(requireNotNull(b.id)).remaining(b.totalAmount),
            )
        }
        return voidItems + replacementItems + unpaidItems
    }

    private fun auditVoided(actorUserId: Long, billId: Long, from: BillStatus, reason: String) {
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = BillingAuditActions.BILL_VOIDED, entityType = BillingAuditEntityTypes.BILL, entityId = billId,
                oldValue = mapOf("status" to from),
                newValue = mapOf("status" to BillStatus.VOIDED, "voidType" to VoidType.MANAGER_VOID, "orderNeedsAttention" to true),
                reason = reason,
            ),
        )
    }

    companion object {
        /**
         * Hoàn tất Void (dùng chung với [RefundService]): Bill VOIDED (MANAGER_VOID), Order LOCKED + needs_attention
         * AWAITING_REPLACEMENT_BILL, xóa status_before_void. Bill và Order phải đã khóa; caller lưu Bill.
         */
        fun completeVoid(bill: Bill, order: Order, actorUserId: Long, now: OffsetDateTime) {
            bill.status = BillStatus.VOIDED
            bill.voidType = VoidType.MANAGER_VOID
            bill.voidedBy = actorUserId
            bill.voidedAt = now
            order.statusBeforeVoid = null
            order.status = OrderStatus.LOCKED
            order.needsAttention = true
            order.attentionReason = OrderAttentionReason.AWAITING_REPLACEMENT_BILL
            order.attentionSetAt = now
        }
    }
}
