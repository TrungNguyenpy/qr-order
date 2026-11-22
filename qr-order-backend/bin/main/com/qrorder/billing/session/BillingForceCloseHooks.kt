package com.qrorder.billing.session

import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.event.BillChangeKind
import com.qrorder.billing.repository.BillRepository
import com.qrorder.billing.service.BillViewMapper
import com.qrorder.billing.service.BillingAuditActions
import com.qrorder.billing.service.BillingAuditEntityTypes
import com.qrorder.billing.service.CURRENT_BILL_STATUSES
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.time.DbClock
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.OrderStatus
import com.qrorder.order.event.OrderChangeKind
import com.qrorder.order.repository.OrderItemRepository
import com.qrorder.order.repository.OrderRepository
import com.qrorder.order.service.LockedSession
import com.qrorder.order.service.OrderAuditActions
import com.qrorder.order.service.OrderAuditEntityTypes
import com.qrorder.order.service.OrderRules
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.TableSessionRepository
import com.qrorder.table.session.SessionForceCloseContext
import com.qrorder.table.session.SessionForceCloseListener
import com.qrorder.table.session.SessionForceCloseValidator
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/*
 * Hoàn thiện force-close phần Bill/Payment (BE-22, BR-S06, BR-P15, BR-P18, P-1, P3) - GĐ5a.
 * Bản chặn tạm `UnsettledOrderForceCloseGuard` của GĐ3 đã bị xóa ở GĐ4 (thay bằng OrderForceCloseHandler cho phần
 * Order chưa có Bill); hai bean dưới đây cắm vào điểm mở rộng SessionForceCloseValidator/Listener của module `table`,
 * chạy trong CÙNG transaction force-close, sau khi bàn + phiên đã khóa FOR UPDATE.
 */

/**
 * Từ chối force-close (không đổi gì) khi:
 * - Bill hiện hành VOID_PENDING (đang chờ hoàn tiền) -> 409 BILL_VOID_PENDING (BR-S06 (i));
 * - còn PaymentTransaction PENDING trên Bill hiện hành -> 409 PENDING_PAYMENT_EXISTS (BR-S06 (v); trigger
 *   trg_bill_invariants cũng cấm Bill WRITTEN_OFF còn Payment PENDING).
 */
@Component
@Order(10)
class BillingForceCloseValidator(
    private val orderRepository: OrderRepository,
    private val billRepository: BillRepository,
    private val mapper: BillViewMapper,
) : SessionForceCloseValidator {
    override fun validate(context: SessionForceCloseContext) {
        val order = orderRepository.findBySessionId(context.sessionId) ?: return
        val bill = billRepository.findFirstByOrderIdAndStatusIn(requireNotNull(order.id), CURRENT_BILL_STATUSES) ?: return
        if (bill.status == BillStatus.VOID_PENDING) {
            throw BusinessException(ErrorCode.BILL_VOID_PENDING, "Bill ${bill.billNumber} đang chờ hoàn tiền, chưa thể force-close")
        }
        if (mapper.money(requireNotNull(bill.id)).tpp > 0) {
            throw BusinessException(
                ErrorCode.PENDING_PAYMENT_EXISTS,
                "Bill ${bill.billNumber} còn giao dịch thanh toán đang chờ, hãy xác nhận SUCCESS/FAILED trước khi force-close",
            )
        }
    }
}

/**
 * Order LOCKED có Bill ACTIVE chưa thu đủ (UNPAID / PARTIALLY_PAID) khi force-close (BR-P15, P3):
 * - shortfall_amount = Total (của Bill) - net_collected (TPS - TRS) > 0;
 * - Bill ACTIVE -> WRITTEN_OFF (written_off_at), payment_status giữ UNPAID/PARTIALLY_PAID; giữ mọi Payment SUCCESS,
 *   không Refund, không VOIDED;
 * - Order -> WRITTEN_OFF (lý do, người, thời điểm); audit ORDER_WRITTEN_OFF + BILL_WRITTEN_OFF.
 * Chạy TRƯỚC OrderForceCloseHandler (GĐ4, không gắn @Order = thứ tự thấp nhất): Order đã WRITTEN_OFF thì handler của
 * GĐ4 bỏ qua. Các trường hợp khác giữ nguyên xử lý GĐ4: Order OPEN / LOCKED không có Bill hiện hành -> thất thu tính
 * từ món (giảm giá 0) hoặc VOIDED; Order PAID (phiên BILLING vì món chưa xong) -> giữ nguyên.
 */
@Component
@Order(10)
class BillingForceCloseListener(
    private val orderRepository: OrderRepository,
    private val billRepository: BillRepository,
    private val itemRepository: OrderItemRepository,
    private val sessionRepository: TableSessionRepository,
    private val tableRepository: DiningTableRepository,
    private val mapper: BillViewMapper,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
) : SessionForceCloseListener {
    override fun onForceClose(context: SessionForceCloseContext) {
        val order = orderRepository.findBySessionId(context.sessionId) ?: return
        if (order.status != OrderStatus.LOCKED) return
        val orderId = requireNotNull(order.id)
        val bill = billRepository.findFirstByOrderIdAndStatusIn(orderId, CURRENT_BILL_STATUSES) ?: return
        if (bill.status != BillStatus.ACTIVE) return // VOID_PENDING đã bị validator chặn
        val billId = requireNotNull(bill.id)
        val money = mapper.money(billId)
        val shortfall = bill.totalAmount - money.netCollected
        if (shortfall <= 0) return // đã thu đủ thì Order phải PAID (không xảy ra); để GĐ4 xử lý như cũ

        val now = dbClock.now()
        bill.status = BillStatus.WRITTEN_OFF
        bill.writtenOffAt = now
        billRepository.saveAndFlush(bill)

        order.needsAttention = false
        order.attentionReason = null
        order.attentionSetAt = null
        order.status = OrderStatus.WRITTEN_OFF
        order.shortfallAmount = shortfall
        order.writeoffReason = context.reason
        order.writtenOffBy = context.actorUserId
        order.writtenOffAt = now
        orderRepository.saveAndFlush(order)

        auditService.record(
            AuditActor.User(context.actorUserId),
            AuditEvent(
                action = OrderAuditActions.ORDER_WRITTEN_OFF, entityType = OrderAuditEntityTypes.ORDERS, entityId = orderId,
                oldValue = mapOf("status" to OrderStatus.LOCKED),
                newValue = mapOf(
                    "status" to OrderStatus.WRITTEN_OFF, "shortfallAmount" to shortfall, "billId" to billId,
                    "billTotal" to bill.totalAmount, "netCollected" to money.netCollected,
                ),
                reason = context.reason,
            ),
        )
        auditService.record(
            AuditActor.User(context.actorUserId),
            AuditEvent(
                action = BillingAuditActions.BILL_WRITTEN_OFF, entityType = BillingAuditEntityTypes.BILL, entityId = billId,
                oldValue = mapOf("status" to BillStatus.ACTIVE, "paymentStatus" to bill.paymentStatus),
                newValue = mapOf("status" to BillStatus.WRITTEN_OFF, "shortfallAmount" to shortfall),
                reason = context.reason,
            ),
        )

        val table = tableRepository.findById(context.tableId).orElse(null)
        val session = sessionRepository.findById(context.sessionId).orElse(null)
        if (table != null && session != null) {
            val locked = LockedSession(table, session)
            events.publishEvent(mapper.event(BillChangeKind.BILL_WRITTEN_OFF, locked, bill, money))
            val items = itemRepository.findAllByOrderIdOrderByIdAsc(orderId)
            events.publishEvent(
                OrderRules.event(
                    OrderChangeKind.ORDER_CLOSED, locked, order, null, items,
                    kitchen = items.any { it.status == OrderItemStatus.CONFIRMED || it.status == OrderItemStatus.PREPARING },
                    staff = true, ready = items.any { it.status == OrderItemStatus.READY },
                ),
            )
        }
    }
}
