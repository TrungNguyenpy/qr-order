package com.qrorder.billing.service

import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.settings.RestaurantSettingsService
import com.qrorder.notification.domain.ServiceCallType
import com.qrorder.notification.servicecall.ServiceCallService
import com.qrorder.order.domain.Order
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.OrderStatus
import com.qrorder.order.repository.OrderItemRepository
import com.qrorder.order.service.LockedSession
import com.qrorder.order.service.OrderItemProgressListener
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.service.TableSessionService
import org.springframework.stereotype.Service

/**
 * Tự đóng phiên (quy tắc chốt GĐ5a, SRS §6.8, §9.7): phiên BILLING chuyển CLOSED ngay khi Bill PAID (Order PAID) VÀ
 * Order không còn món tính tiền chưa phục vụ xong (CONFIRMED, PREPARING, READY - và thận trọng cả PENDING). Khi CLOSED,
 * bàn -> CLEANING (hoặc AVAILABLE nếu `cleaning_auto_release`) qua [TableSessionService.closeSession] (cùng transaction,
 * phát `/topic/session/{id}` CLOSED và `/topic/tables`). ServiceCall còn mở của phiên được tự RESOLVED.
 *
 * Được gọi ở hai nơi, luôn trong transaction đã khóa bàn + phiên:
 * 1. Sau khi Bill vừa PAID (PaymentService, BillService khi Total = 0).
 * 2. Sau mỗi lần món đổi trạng thái ([OrderItemProgressListener]): Bill đã PAID từ trước, món cuối cùng vừa SERVED
 *    (hoặc bị từ chối/hủy) thì đóng phiên.
 */
@Service
class SessionSettlementService(
    private val itemRepository: OrderItemRepository,
    private val tableSessionService: TableSessionService,
    private val settingsService: RestaurantSettingsService,
    private val serviceCallService: ServiceCallService,
    private val auditService: AuditService,
) : OrderItemProgressListener {

    /** Trả true nếu phiên vừa được đóng. */
    fun closeIfSettled(locked: LockedSession, order: Order, actorUserId: Long?): Boolean {
        if (locked.session.status != SessionStatus.BILLING || order.status != OrderStatus.PAID) return false
        val items = itemRepository.findAllByOrderIdOrderByIdAsc(requireNotNull(order.id))
        if (items.any { it.status in UNFINISHED }) return false

        serviceCallService.autoResolve(
            locked.sessionId, locked.tableId, locked.table.code, ServiceCallType.entries, "Phiên đã thanh toán xong và đóng",
        )
        val closed = tableSessionService.closeSession(
            locked.table, locked.session, SessionStatus.CLOSED, actorUserId, null, settingsService.current(),
        )
        auditService.record(
            actorUserId?.let { AuditActor.User(it) } ?: AuditActor.System,
            AuditEvent(
                action = BillingAuditActions.SESSION_CLOSED, entityType = BillingAuditEntityTypes.TABLE_SESSION,
                entityId = locked.sessionId,
                oldValue = mapOf("status" to SessionStatus.BILLING, "tableStatus" to closed.previousTableStatus),
                newValue = mapOf("status" to SessionStatus.CLOSED, "tableStatus" to locked.table.status, "orderId" to order.id),
            ),
        )
        return true
    }

    override fun afterItemStatusChanged(locked: LockedSession, order: Order, actorUserId: Long?) {
        closeIfSettled(locked, order, actorUserId)
    }

    companion object {
        /** Món chưa xong: còn đang chờ/nấu/chờ mang ra. */
        val UNFINISHED: Set<OrderItemStatus> =
            setOf(OrderItemStatus.PENDING, OrderItemStatus.CONFIRMED, OrderItemStatus.PREPARING, OrderItemStatus.READY)
    }
}
