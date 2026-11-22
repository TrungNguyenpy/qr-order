package com.qrorder.order.session

import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.settings.RestaurantSettingsService
import com.qrorder.common.time.DbClock
import com.qrorder.order.domain.BatchStatus
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.OrderStatus
import com.qrorder.order.domain.StatusActor
import com.qrorder.order.event.OrderChangeKind
import com.qrorder.order.repository.OrderBatchRepository
import com.qrorder.order.repository.OrderItemRepository
import com.qrorder.order.repository.OrderRepository
import com.qrorder.order.service.OrderAuditActions
import com.qrorder.order.service.OrderAuditEntityTypes
import com.qrorder.order.service.OrderReasonCodes
import com.qrorder.order.service.OrderRules
import com.qrorder.order.service.ShortfallCalculator
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.session.SessionExpiryListener
import com.qrorder.table.session.SessionForceCloseContext
import com.qrorder.table.session.SessionForceCloseListener
import com.qrorder.table.session.UnpaidOrderChecker
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/*
 * Cài đặt các điểm mở rộng phiên bàn (module table/session) từ phía module `order` - Giai đoạn 4.
 * Chạy trong CÙNG transaction với thao tác đóng phiên, SAU khi bàn + phiên đã khóa FOR UPDATE (cùng thứ tự khóa).
 * GĐ5-GĐ6 bổ sung (không sửa luồng lõi của `table`): validator Bill VOID_PENDING / Payment PENDING, và khi có Bill ACTIVE
 * thì Total lấy từ Bill, shortfall = Total - net_collected, Bill chuyển WRITTEN_OFF/VOIDED theo BR-P15.
 */

/** SQL dùng chung: phiên có "đơn chưa thanh toán" (BR-S07) - Order OPEN có món chưa CANCELLED/REJECTED, hoặc Order LOCKED. */
internal const val UNPAID_ORDER_EXISTS_SQL = """
    SELECT EXISTS (
        SELECT 1 FROM orders o
         WHERE o.session_id = ?
           AND (o.status = 'LOCKED'
                OR (o.status = 'OPEN' AND EXISTS (
                        SELECT 1 FROM order_item i
                         WHERE i.order_id = o.id AND i.status NOT IN ('CANCELLED', 'REJECTED')))))"""

/** BR-S07 (Scheduler): còn đơn chưa thanh toán thì không EXPIRED mà gắn needs_attention. */
@Component
class OrderUnpaidChecker(private val jdbc: JdbcTemplate) : UnpaidOrderChecker {
    override fun hasUnpaidOrder(sessionId: Long): Boolean =
        jdbc.queryForObject(UNPAID_ORDER_EXISTS_SQL, Boolean::class.java, sessionId) == true
}

/**
 * Order OPEN không còn món nào chưa CANCELLED/REJECTED -> VOIDED khi phiên EXPIRED (§9.7 dòng 18).
 * (Force-close do [OrderForceCloseHandler] xử lý đầy đủ.)
 */
@Component
class EmptyOrderVoider(private val jdbc: JdbcTemplate) : SessionExpiryListener {
    override fun onExpire(sessionId: Long) {
        jdbc.update(
            """UPDATE orders o SET status = 'VOIDED', voided_at = now(), updated_at = now(), version = o.version + 1
                WHERE o.session_id = ? AND o.status = 'OPEN'
                  AND NOT EXISTS (SELECT 1 FROM order_item i
                                   WHERE i.order_id = o.id AND i.status NOT IN ('CANCELLED', 'REJECTED'))""",
            sessionId,
        )
    }
}

/**
 * Force-close của Manager (BR-S06 (iii)/(iv), BR-O13, BR-P15, BR-P18) - thay thế bản chặn của GĐ3
 * (UnsettledOrderForceCloseGuard). Chạy TRƯỚC khi phiên chuyển FORCE_CLOSED:
 * 1. Order OPEN: mọi đợt và món PENDING -> REJECTED với reasonCode `SESSION_FORCE_CLOSED` (actor SYSTEM; đợt REJECTED).
 * 2. Còn món tính tiền (CONFIRMED/PREPARING/READY/SERVED) có thành tiền > 0 (BR-P18) -> Order WRITTEN_OFF, ghi
 *    `shortfall_amount` = Total theo BR-P02 (giảm giá 0, chưa có Bill/Payment ở GĐ4), lý do, người, thời điểm; audit
 *    ORDER_WRITTEN_OFF. Không có món tính tiền -> Order VOIDED, không thất thu; audit ORDER_VOIDED.
 * 3. Order đã PAID/VOIDED/WRITTEN_OFF: giữ nguyên. Phiên chưa có Order: không làm gì.
 * Món đang chế biến (CONFIRMED/PREPARING/READY) giữ nguyên trạng thái (đã tính vào thất thu).
 */
@Component
class OrderForceCloseHandler(
    private val orderRepository: OrderRepository,
    private val batchRepository: OrderBatchRepository,
    private val itemRepository: OrderItemRepository,
    private val tableRepository: DiningTableRepository,
    private val settingsService: RestaurantSettingsService,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
) : SessionForceCloseListener {

    override fun onForceClose(context: SessionForceCloseContext) {
        val order = orderRepository.findBySessionId(context.sessionId) ?: return
        if (order.status != OrderStatus.OPEN && order.status != OrderStatus.LOCKED) return
        val orderId = requireNotNull(order.id)
        val now = dbClock.now()
        val items = itemRepository.findAllByOrderIdOrderByIdAsc(orderId)

        // 1. Từ chối món/đợt PENDING (chỉ Order OPEN; Order LOCKED không có món PENDING theo BR-O11).
        val rejected = if (order.status == OrderStatus.OPEN) {
            items.filter { it.status == OrderItemStatus.PENDING }.onEach {
                it.changeStatus(OrderItemStatus.REJECTED, StatusActor.System, now, OrderReasonCodes.SESSION_FORCE_CLOSED)
            }
        } else emptyList()
        if (rejected.isNotEmpty()) {
            itemRepository.saveAllAndFlush(rejected)
            batchRepository.findAllByOrderIdOrderBySeqAsc(orderId).filter { it.status == BatchStatus.PENDING }.forEach { b ->
                b.status = BatchStatus.REJECTED
                b.reasonCode = OrderReasonCodes.SESSION_FORCE_CLOSED
                b.decidedAt = now
                batchRepository.save(b)
            }
            batchRepository.flush()
        }

        // 2. Thất thu hoặc VOIDED (BR-P15, BR-P18).
        val previous = order.status
        // CHECK ck_orders_attention: cờ needs_attention chỉ hợp lệ khi Order LOCKED; đóng Order thì gỡ cờ (BR-P17).
        order.needsAttention = false
        order.attentionReason = null
        order.attentionSetAt = null
        if (ShortfallCalculator.hasBillableValue(items)) {
            val subtotal = ShortfallCalculator.billableSubtotal(items)
            val shortfall = ShortfallCalculator.total(subtotal, settingsService.current().serviceFeeRate)
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
                    oldValue = mapOf("status" to previous),
                    newValue = mapOf("status" to OrderStatus.WRITTEN_OFF, "shortfallAmount" to shortfall, "billableSubtotal" to subtotal),
                    reason = context.reason,
                ),
            )
        } else {
            order.status = OrderStatus.VOIDED
            order.voidedAt = now
            orderRepository.saveAndFlush(order)
            auditService.record(
                AuditActor.User(context.actorUserId),
                AuditEvent(
                    action = OrderAuditActions.ORDER_VOIDED, entityType = OrderAuditEntityTypes.ORDERS, entityId = orderId,
                    oldValue = mapOf("status" to previous), newValue = mapOf("status" to OrderStatus.VOIDED), reason = context.reason,
                ),
            )
        }

        val table = tableRepository.findById(context.tableId).orElse(null)
        events.publishEvent(
            OrderRules.event(
                OrderChangeKind.ORDER_CLOSED, context.sessionId, context.tableId, table?.code ?: "", order, null, items,
                kitchen = items.any { it.status == OrderItemStatus.CONFIRMED || it.status == OrderItemStatus.PREPARING },
                staff = true, ready = items.any { it.status == OrderItemStatus.READY },
            ),
        )
    }
}
