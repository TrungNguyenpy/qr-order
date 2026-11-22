package com.qrorder.order.service

import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.domain.RequesterType
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.settings.RestaurantSettingsService
import com.qrorder.common.time.DbClock
import com.qrorder.order.domain.BatchStatus
import com.qrorder.order.domain.Order
import com.qrorder.order.domain.OrderBatch
import com.qrorder.order.domain.OrderStatus
import com.qrorder.order.dto.PlaceOrderRequest
import com.qrorder.order.dto.StaffPlaceOrderResponse
import com.qrorder.order.event.OrderChangeKind
import com.qrorder.order.repository.OrderBatchRepository
import com.qrorder.order.repository.OrderItemRepository
import com.qrorder.order.repository.OrderRepository
import com.qrorder.table.domain.SessionStatus
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * FR-ORD-05 (BE-37 a): nhân viên (STAFF/MANAGER) đặt món hộ khách vào một phiên bàn đang mở.
 *
 * Dùng chung [OrderLinePreparer] với luồng khách nên cùng quy tắc món/option/số lượng/snapshot (BR-O01..O03, BE-04)
 * và cùng idempotency `clientRequestId` (BR-O04). Khác luồng khách:
 * - Không rate limit của khách (BR-S09) và không cần PIN bàn: nhân viên đang đứng tại nhà hàng.
 * - Đợt do nhân viên tạo luôn `requires_confirmation = false`: đợt ACCEPTED và món CONFIRMED ngay (nhân viên tự nhập
 *   nên không cần duyệt lại); bếp nhận ngay. Không đặt `table_session.verified_at` (chỉ việc xác nhận đợt của khách mới
 *   chứng minh phiên là thật - BR-S04), nên chế độ duyệt đợt của khách không bị thay đổi.
 * - Người đặt là nhân viên: `order_batch.submitted_by_type = STAFF`, `submitted_by_user_id`, `order_item.created_by_user_id`
 *   (lịch sử món do trigger ghi người đặt là STAFF) và ghi audit `ORDER_ITEM_ADDED_BY_STAFF`.
 *
 * Chỉ khi phiên OPEN và Order (nếu đã có) OPEN (BR-O12). Mọi thay đổi chạy trong MỘT transaction giữ khóa bàn rồi phiên
 * (cùng thứ tự với khách, force-close và Tính tiền nên không deadlock).
 */
@Service
class StaffOrderService(
    private val locker: SessionOrderLocker,
    private val settingsService: RestaurantSettingsService,
    private val orderRepository: OrderRepository,
    private val batchRepository: OrderBatchRepository,
    private val itemRepository: OrderItemRepository,
    private val linePreparer: OrderLinePreparer,
    private val queryService: OrderQueryService,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun place(userId: Long, sessionId: Long, request: PlaceOrderRequest): StaffPlaceOrderResponse {
        val clientRequestId = requireNotNull(request.clientRequestId)
        val lines = requireNotNull(request.items)
        val locked = locker.lock(sessionId)
        val session = locked.session

        batchRepository.findBySessionIdAndClientRequestId(sessionId, clientRequestId)?.let { existing ->
            // Cùng clientRequestId nhưng đợt do khách gửi: không trả đợt của khách cho nhân viên như thể là kết quả của họ.
            if (existing.submittedByType != RequesterType.STAFF) throw BusinessException(ErrorCode.DUPLICATE_CLIENT_REQUEST)
            return view(existing.orderId, requireNotNull(existing.id), replayed = true)
        }

        if (session.status != SessionStatus.OPEN) throw BusinessException(ErrorCode.SESSION_NOT_OPEN)
        val existingOrder = orderRepository.findBySessionId(sessionId)
        if (existingOrder != null && existingOrder.status != OrderStatus.OPEN) {
            throw BusinessException(ErrorCode.ORDER_NOT_OPEN, "Order đang ${existingOrder.status}, không nhận thêm đợt mới")
        }

        val prepared = linePreparer.prepare(lines, settingsService.current())
        val now = dbClock.now()
        val order: Order = existingOrder ?: orderRepository.saveAndFlush(Order(sessionId))
        val orderId = requireNotNull(order.id)

        val batch = OrderBatch(
            orderId = orderId, sessionId = sessionId, seq = batchRepository.maxSeq(orderId) + 1,
            clientRequestId = clientRequestId, requiresConfirmation = false, submittedByType = RequesterType.STAFF,
        ).apply {
            submittedByUserId = userId
            status = BatchStatus.ACCEPTED
            decidedBy = userId
            decidedAt = now
        }
        val savedBatch = batchRepository.saveAndFlush(batch)
        val batchId = requireNotNull(savedBatch.id)

        val savedItems = linePreparer.persist(
            orderId = orderId, batchId = batchId, prepared = prepared, confirmedAt = now, deviceId = null, createdByUserId = userId,
        )

        session.lastActivityAt = now
        locker.save(session)

        auditService.record(
            AuditActor.User(userId),
            AuditEvent(
                action = OrderAuditActions.ORDER_ITEM_ADDED_BY_STAFF, entityType = OrderAuditEntityTypes.ORDER_BATCH, entityId = batchId,
                newValue = mapOf(
                    "sessionId" to sessionId, "orderId" to orderId, "seq" to savedBatch.seq,
                    "items" to savedItems.map {
                        mapOf(
                            "itemId" to it.id, "productId" to it.productId, "productName" to it.productName,
                            "quantity" to it.quantity, "lineTotal" to it.lineTotal,
                        )
                    },
                ),
            ),
        )
        events.publishEvent(
            OrderRules.event(OrderChangeKind.BATCH_SUBMITTED, locked, order, batchId, savedItems, kitchen = true, staff = true),
        )
        return view(orderId, batchId, replayed = false)
    }

    private fun view(orderId: Long, batchId: Long, replayed: Boolean): StaffPlaceOrderResponse =
        StaffPlaceOrderResponse(
            replayed = replayed,
            batch = queryService.staffBatch(batchId),
            billableTotal = ShortfallCalculator.billableSubtotal(itemRepository.findAllByOrderIdOrderByIdAsc(orderId)),
        )
}
