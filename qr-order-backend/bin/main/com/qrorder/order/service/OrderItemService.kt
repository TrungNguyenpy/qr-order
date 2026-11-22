package com.qrorder.order.service

import com.qrorder.auth.security.GuestPrincipal
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.domain.RequesterType
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.common.time.DbClock
import com.qrorder.order.domain.BatchStatus
import com.qrorder.order.domain.CancelRequestStatus
import com.qrorder.order.domain.ItemCancelRequest
import com.qrorder.order.domain.Order
import com.qrorder.order.domain.OrderItem
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.StatusActor
import com.qrorder.order.dto.CancelRequestResponse
import com.qrorder.order.dto.ItemActionResponse
import com.qrorder.order.event.OrderChangeKind
import com.qrorder.order.repository.ItemCancelRequestRepository
import com.qrorder.order.repository.OrderBatchRepository
import com.qrorder.order.repository.OrderItemRepository
import com.qrorder.order.repository.OrderRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Action ghi audit_log của module order (DATABASE_DESIGN.md §6). */
object OrderAuditActions {
    const val ITEM_CANCEL_APPROVED = "ITEM_CANCEL_APPROVED"
    const val ITEM_CANCEL_DENIED = "ITEM_CANCEL_DENIED"
    /** Bắt buộc lý do (CHECK ck_audit_log_reason_required). */
    const val ORDER_WRITTEN_OFF = "ORDER_WRITTEN_OFF"
    const val ORDER_VOIDED = "ORDER_VOIDED"
}

object OrderAuditEntityTypes {
    const val ORDERS = "orders"
    const val ITEM_CANCEL_REQUEST = "item_cancel_request"
}

/** Lý do chuẩn của hệ thống (UPPER_SNAKE_CASE, BR-O08). */
object OrderReasonCodes {
    const val GUEST_CANCELLED = "GUEST_CANCELLED"
    const val CANCEL_APPROVED = "CANCEL_REQUEST_APPROVED"
    const val OUT_OF_STOCK = "OUT_OF_STOCK"
    const val SESSION_FORCE_CLOSED = "SESSION_FORCE_CLOSED"
}

/**
 * Thao tác trên từng món: bếp (PREPARING/READY/từ chối), phục vụ (SERVED), khách hủy, yêu cầu hủy món đang nấu và
 * Manager duyệt (FR-KIT-01..03, BR-O05, BR-O06, BR-O08, BR-O09, BR-O12).
 *
 * Mọi thay đổi trạng thái đi qua [OrderItem.changeStatus] (DB-14) và được thực hiện sau khi khóa bàn + phiên.
 */
@Service
class OrderItemService(
    private val locker: SessionOrderLocker,
    private val orderRepository: OrderRepository,
    private val batchRepository: OrderBatchRepository,
    private val itemRepository: OrderItemRepository,
    private val cancelRequestRepository: ItemCancelRequestRepository,
    private val synchronizer: BatchStatusSynchronizer,
    private val mapper: OrderViewMapper,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
    private val progressListeners: ObjectProvider<OrderItemProgressListener>,
) {
    // =========================================================================================== Bếp / phục vụ

    /** Bếp: CONFIRMED -> PREPARING -> READY (FR-KIT-02). */
    @Transactional
    fun advanceInKitchen(userId: Long, itemId: Long, target: OrderItemStatus): ItemActionResponse {
        if (target != OrderItemStatus.PREPARING && target != OrderItemStatus.READY) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "Bếp chỉ chuyển món sang PREPARING hoặc READY")
        }
        return transition(userId, itemId, target, OrderChangeKind.ITEM_STATUS_CHANGED, kitchen = true, staff = false, ready = target == OrderItemStatus.READY)
    }

    /** Staff/Manager: READY -> SERVED (món đã mang ra bàn). */
    @Transactional
    fun serve(userId: Long, itemId: Long): ItemActionResponse =
        transition(userId, itemId, OrderItemStatus.SERVED, OrderChangeKind.ITEM_STATUS_CHANGED, kitchen = false, staff = false, ready = true)

    private fun transition(
        userId: Long,
        itemId: Long,
        target: OrderItemStatus,
        kind: OrderChangeKind,
        kitchen: Boolean,
        staff: Boolean,
        ready: Boolean,
    ): ItemActionResponse {
        val ctx = lockItem(itemId)
        val now = dbClock.now()
        ctx.item.changeStatus(target, StatusActor.Staff(userId), now)
        itemRepository.saveAndFlush(ctx.item)
        if (target == OrderItemStatus.SERVED) {
            OrderRules.denyPendingRequest(cancelRequestRepository, itemId, userId, "Món đã được phục vụ trước khi duyệt", now)
        }
        val batchStatus = syncBatch(ctx, now)
        events.publishEvent(OrderRules.event(kind, ctx.locked, ctx.order, ctx.item.batchId, listOf(ctx.item), kitchen, staff, ready))
        notifyProgress(ctx, userId)
        return ItemActionResponse(mapper.itemResponses(listOf(ctx.item)).single(), batchStatus)
    }

    /** Bếp từ chối món CONFIRMED/PREPARING (hết nguyên liệu...); PREPARING -> wasted (BR-O06). Order phải OPEN (BR-O12). */
    @Transactional
    fun kitchenReject(userId: Long, itemId: Long, reasonCode: String, reasonNote: String?): ItemActionResponse {
        val ctx = lockItem(itemId)
        if (ctx.item.status != OrderItemStatus.CONFIRMED && ctx.item.status != OrderItemStatus.PREPARING) {
            throw BusinessException(ErrorCode.ORDER_ITEM_STATE_INVALID, "Bếp chỉ từ chối món CONFIRMED hoặc PREPARING (đang ${ctx.item.status})")
        }
        OrderRules.requireOpen(ctx.order)
        val now = dbClock.now()
        ctx.item.changeStatus(OrderItemStatus.REJECTED, StatusActor.Staff(userId), now, reasonCode, reasonNote)
        itemRepository.saveAndFlush(ctx.item)
        OrderRules.denyPendingRequest(cancelRequestRepository, itemId, userId, "Món đã bị bếp từ chối", now)
        val batchStatus = syncBatch(ctx, now)
        events.publishEvent(
            OrderRules.event(OrderChangeKind.ITEM_STATUS_CHANGED, ctx.locked, ctx.order, ctx.item.batchId, listOf(ctx.item), kitchen = true, staff = true),
        )
        notifyProgress(ctx, userId)
        return ItemActionResponse(mapper.itemResponses(listOf(ctx.item)).single(), batchStatus)
    }

    // =========================================================================================== Khách

    /** Khách hủy món PENDING/CONFIRMED khi Order còn OPEN (BR-O05, BR-O06); món đã nấu phải gửi yêu cầu hủy. */
    @Transactional
    fun guestCancel(principal: GuestPrincipal, itemId: Long, note: String?): ItemActionResponse {
        val ctx = lockGuestItem(principal, itemId)
        OrderRules.requireOpen(ctx.order)
        val status = ctx.item.status
        if (status != OrderItemStatus.PENDING && status != OrderItemStatus.CONFIRMED) {
            throw BusinessException(
                ErrorCode.ORDER_ITEM_STATE_INVALID,
                if (status == OrderItemStatus.PREPARING || status == OrderItemStatus.READY) "Món đã vào chế biến, vui lòng gửi yêu cầu hủy món"
                else "Món đang $status, không thể hủy",
            )
        }
        val now = dbClock.now()
        ctx.item.changeStatus(OrderItemStatus.CANCELLED, StatusActor.Guest(principal.deviceId), now, OrderReasonCodes.GUEST_CANCELLED, note?.trim()?.takeIf { it.isNotEmpty() })
        itemRepository.saveAndFlush(ctx.item)
        ctx.locked.session.lastActivityAt = now
        locker.save(ctx.locked.session)
        syncBatch(ctx, now)
        events.publishEvent(
            OrderRules.event(
                OrderChangeKind.ITEM_CANCELLED, ctx.locked, ctx.order, ctx.item.batchId, listOf(ctx.item),
                kitchen = status == OrderItemStatus.CONFIRMED, staff = true,
            ),
        )
        notifyProgress(ctx, null)
        return ItemActionResponse(mapper.itemResponses(listOf(ctx.item)).single(), batchStatusOf(ctx))
    }

    /** Khách gửi yêu cầu hủy món PREPARING/READY; Manager duyệt (BR-O06). Mỗi món tối đa một yêu cầu PENDING. */
    @Transactional
    fun requestCancel(principal: GuestPrincipal, itemId: Long, reason: String?): CancelRequestResponse {
        val ctx = lockGuestItem(principal, itemId)
        OrderRules.requireOpen(ctx.order)
        val status = ctx.item.status
        if (status != OrderItemStatus.PREPARING && status != OrderItemStatus.READY) {
            throw BusinessException(ErrorCode.CANCEL_REQUEST_NOT_ALLOWED, "Chỉ gửi yêu cầu hủy cho món đang chế biến hoặc đã sẵn sàng (món đang $status)")
        }
        if (cancelRequestRepository.findFirstByOrderItemIdAndStatus(itemId, CancelRequestStatus.PENDING) != null) {
            throw BusinessException(ErrorCode.CANCEL_REQUEST_ALREADY_PENDING)
        }
        val request = ItemCancelRequest(itemId, status, RequesterType.GUEST).apply {
            requestedByDeviceId = principal.deviceId
            requestReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        }
        val saved = cancelRequestRepository.saveAndFlush(request)
        val now = dbClock.now()
        ctx.locked.session.lastActivityAt = now
        locker.save(ctx.locked.session)
        events.publishEvent(
            OrderRules.event(OrderChangeKind.CANCEL_REQUESTED, ctx.locked, ctx.order, ctx.item.batchId, listOf(ctx.item), staff = true),
        )
        return toResponse(saved, ctx)
    }

    // =========================================================================================== Manager (ITEM_CANCEL_APPROVE)

    @Transactional
    fun approveCancel(managerId: Long, requestId: Long, note: String?): CancelRequestResponse {
        val itemId = cancelRequestRepository.findOrderItemIdById(requestId) ?: throw ResourceNotFoundException("yêu cầu hủy", requestId)
        val ctx = lockItem(itemId)
        val request = requirePending(requestId)
        OrderRules.requireOpen(ctx.order)
        val status = ctx.item.status
        if (status != OrderItemStatus.PREPARING && status != OrderItemStatus.READY) {
            throw BusinessException(ErrorCode.ORDER_ITEM_STATE_INVALID, "Món đang $status, không còn có thể hủy theo yêu cầu")
        }
        val now = dbClock.now()
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        ctx.item.changeStatus(OrderItemStatus.CANCELLED, StatusActor.Staff(managerId), now, OrderReasonCodes.CANCEL_APPROVED, cleanNote)
        itemRepository.saveAndFlush(ctx.item)
        request.status = CancelRequestStatus.APPROVED
        request.decidedBy = managerId
        request.decidedAt = now
        request.decisionReason = cleanNote
        val saved = cancelRequestRepository.saveAndFlush(request)
        auditService.record(
            AuditActor.User(managerId),
            AuditEvent(
                action = OrderAuditActions.ITEM_CANCEL_APPROVED, entityType = OrderAuditEntityTypes.ITEM_CANCEL_REQUEST, entityId = requestId,
                oldValue = mapOf("requestStatus" to CancelRequestStatus.PENDING, "itemStatus" to status),
                newValue = mapOf("requestStatus" to CancelRequestStatus.APPROVED, "itemStatus" to OrderItemStatus.CANCELLED, "wasted" to ctx.item.wasted),
                reason = cleanNote,
            ),
        )
        syncBatch(ctx, now)
        events.publishEvent(
            OrderRules.event(OrderChangeKind.CANCEL_DECIDED, ctx.locked, ctx.order, ctx.item.batchId, listOf(ctx.item), kitchen = true, staff = true),
        )
        notifyProgress(ctx, managerId)
        return toResponse(saved, ctx)
    }

    @Transactional
    fun denyCancel(managerId: Long, requestId: Long, reason: String): CancelRequestResponse {
        val itemId = cancelRequestRepository.findOrderItemIdById(requestId) ?: throw ResourceNotFoundException("yêu cầu hủy", requestId)
        val ctx = lockItem(itemId)
        val request = requirePending(requestId)
        val now = dbClock.now()
        request.status = CancelRequestStatus.DENIED
        request.decidedBy = managerId
        request.decidedAt = now
        request.decisionReason = reason.trim()
        val saved = cancelRequestRepository.saveAndFlush(request)
        auditService.record(
            AuditActor.User(managerId),
            AuditEvent(
                action = OrderAuditActions.ITEM_CANCEL_DENIED, entityType = OrderAuditEntityTypes.ITEM_CANCEL_REQUEST, entityId = requestId,
                oldValue = mapOf("requestStatus" to CancelRequestStatus.PENDING),
                newValue = mapOf("requestStatus" to CancelRequestStatus.DENIED),
                reason = reason.trim(),
            ),
        )
        events.publishEvent(
            OrderRules.event(OrderChangeKind.CANCEL_DECIDED, ctx.locked, ctx.order, ctx.item.batchId, listOf(ctx.item), staff = true),
        )
        return toResponse(saved, ctx)
    }

    // =========================================================================================== Nội bộ

    private class ItemContext(val locked: LockedSession, val order: Order, val item: OrderItem)

    /** Khóa bàn + phiên của món rồi nạp lại món/Order (dữ liệu mới nhất sau khi có khóa). */
    private fun lockItem(itemId: Long): ItemContext {
        val orderId = itemRepository.findOrderIdById(itemId) ?: throw ResourceNotFoundException("món", itemId)
        val sessionId = orderRepository.findSessionIdById(orderId) ?: throw ResourceNotFoundException("order", orderId)
        val locked = locker.lock(sessionId)
        val order = orderRepository.findById(orderId).orElseThrow { ResourceNotFoundException("order", orderId) }
        val item = itemRepository.findById(itemId).orElseThrow { ResourceNotFoundException("món", itemId) }
        return ItemContext(locked, order, item)
    }

    /** Món phải thuộc phiên của khách; ngược lại trả 404 (không lộ món của bàn khác). */
    private fun lockGuestItem(principal: GuestPrincipal, itemId: Long): ItemContext {
        val orderId = itemRepository.findOrderIdById(itemId) ?: throw ResourceNotFoundException("món", itemId)
        val sessionId = orderRepository.findSessionIdById(orderId) ?: throw ResourceNotFoundException("món", itemId)
        if (sessionId != principal.sessionId) throw ResourceNotFoundException("món", itemId)
        return lockItem(itemId)
    }

    private fun requirePending(requestId: Long): ItemCancelRequest {
        val request = cancelRequestRepository.findById(requestId).orElseThrow { ResourceNotFoundException("yêu cầu hủy", requestId) }
        if (request.status != CancelRequestStatus.PENDING) throw BusinessException(ErrorCode.CANCEL_REQUEST_NOT_PENDING)
        return request
    }

    private fun syncBatch(ctx: ItemContext, now: java.time.OffsetDateTime): BatchStatus {
        val batch = batchRepository.findById(ctx.item.batchId).orElseThrow { ResourceNotFoundException("đợt order", ctx.item.batchId) }
        val items = itemRepository.findAllByBatchIdInOrderByIdAsc(listOf(requireNotNull(batch.id)))
        if (synchronizer.sync(batch, items, now)) batchRepository.saveAndFlush(batch)
        return batch.status
    }

    /**
     * GĐ5a: báo cho module billing biết món vừa đổi trạng thái (tự đóng phiên khi Bill đã PAID và món cuối cùng đã xong).
     * Hủy/từ chối chỉ xảy ra khi Order OPEN (BR-O12) nên trên thực tế chỉ SERVED (và PREPARING/READY) chạm tới phiên BILLING.
     */
    private fun notifyProgress(ctx: ItemContext, actorUserId: Long?) {
        progressListeners.orderedStream().forEach { it.afterItemStatusChanged(ctx.locked, ctx.order, actorUserId) }
    }

    private fun batchStatusOf(ctx: ItemContext): BatchStatus =
        batchRepository.findById(ctx.item.batchId).orElseThrow { ResourceNotFoundException("đợt order", ctx.item.batchId) }.status

    private fun toResponse(r: ItemCancelRequest, ctx: ItemContext) = CancelRequestResponse(
        id = requireNotNull(r.id), orderItemId = r.orderItemId, orderId = ctx.item.orderId, sessionId = ctx.locked.sessionId,
        tableCode = ctx.locked.table.code, productName = ctx.item.productName, quantity = ctx.item.quantity,
        itemStatusAtRequest = r.itemStatusAtRequest, currentItemStatus = ctx.item.status, status = r.status,
        requestReason = r.requestReason, requestedAt = r.requestedAt, decidedBy = r.decidedBy, decidedAt = r.decidedAt,
        decisionReason = r.decisionReason, version = r.version,
    )
}
