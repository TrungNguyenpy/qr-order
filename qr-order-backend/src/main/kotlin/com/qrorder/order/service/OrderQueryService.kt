package com.qrorder.order.service

import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.common.settings.RestaurantSettingsService
import com.qrorder.common.time.DbClock
import com.qrorder.order.domain.BatchStatus
import com.qrorder.order.domain.CancelRequestStatus
import com.qrorder.order.domain.OrderBatch
import com.qrorder.order.domain.OrderItem
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.dto.CancelRequestResponse
import com.qrorder.order.dto.GuestOrderResponse
import com.qrorder.order.dto.KitchenItemResponse
import com.qrorder.order.dto.OrderOptionResponse
import com.qrorder.order.dto.StaffBatchResponse
import com.qrorder.order.dto.StaffOrderResponse
import com.qrorder.order.repository.ItemCancelRequestRepository
import com.qrorder.order.repository.OrderBatchRepository
import com.qrorder.order.repository.OrderItemOptionRepository
import com.qrorder.order.repository.OrderItemRepository
import com.qrorder.order.repository.OrderRepository
import com.qrorder.table.domain.DiningTable
import com.qrorder.table.domain.TableSession
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.TableSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

/** Truy vấn đọc cho khách, Staff và bếp (KDS). Không có thay đổi dữ liệu. */
@Service
class OrderQueryService(
    private val orderRepository: OrderRepository,
    private val batchRepository: OrderBatchRepository,
    private val itemRepository: OrderItemRepository,
    private val optionRepository: OrderItemOptionRepository,
    private val cancelRequestRepository: ItemCancelRequestRepository,
    private val sessionRepository: TableSessionRepository,
    private val tableRepository: DiningTableRepository,
    private val settingsService: RestaurantSettingsService,
    private val mapper: OrderViewMapper,
    private val dbClock: DbClock,
) {
    /** Order của phiên cho khách (chưa gửi đợt nào: orderId null, danh sách đợt rỗng). */
    @Transactional(readOnly = true)
    fun guestOrder(sessionId: Long, replayed: Boolean = false, batchId: Long? = null): GuestOrderResponse {
        val order = orderRepository.findBySessionId(sessionId)
            ?: return GuestOrderResponse(null, sessionId, null, emptyList(), 0, replayed, batchId)
        val items = itemRepository.findAllByOrderIdOrderByIdAsc(requireNotNull(order.id))
        val batches = batchRepository.findAllByOrderIdOrderBySeqAsc(requireNotNull(order.id))
        return GuestOrderResponse(
            orderId = order.id, sessionId = sessionId, status = order.status,
            batches = mapper.batchResponses(batches, items), billableTotal = ShortfallCalculator.billableSubtotal(items),
            replayed = replayed, batchId = batchId,
        )
    }

    /** Order của một phiên cho nhân viên (FLOOR). */
    @Transactional(readOnly = true)
    fun staffOrder(sessionId: Long): StaffOrderResponse {
        val session = sessionRepository.findById(sessionId).orElseThrow { ResourceNotFoundException("phiên bàn", sessionId) }
        val table = tableRepository.findById(session.tableId).orElseThrow { ResourceNotFoundException("bàn", session.tableId) }
        val order = orderRepository.findBySessionId(sessionId)
            ?: return StaffOrderResponse(null, sessionId, table.code, null, emptyList(), 0)
        val items = itemRepository.findAllByOrderIdOrderByIdAsc(requireNotNull(order.id))
        val batches = batchRepository.findAllByOrderIdOrderBySeqAsc(requireNotNull(order.id))
        return StaffOrderResponse(
            orderId = order.id, sessionId = sessionId, tableCode = table.code, status = order.status,
            batches = staffBatches(batches, items, mapOf(sessionId to session), mapOf(table.id!! to table)),
            billableTotal = ShortfallCalculator.billableSubtotal(items),
        )
    }

    /** Hàng đợi đợt chờ xác nhận (FR-ORD-06): cũ trước, kèm người đang giữ claim. */
    @Transactional(readOnly = true)
    fun pendingBatches(): List<StaffBatchResponse> {
        val batches = batchRepository.findAllByStatusOrderBySubmittedAtAscIdAsc(BatchStatus.PENDING)
        if (batches.isEmpty()) return emptyList()
        val items = itemRepository.findAllByBatchIdInOrderByIdAsc(batches.map { requireNotNull(it.id) })
        val sessions = sessionRepository.findAllById(batches.map { it.sessionId }.distinct()).associateBy { requireNotNull(it.id) }
        val tables = tableRepository.findAllById(sessions.values.map { it.tableId }.distinct()).associateBy { requireNotNull(it.id) }
        return staffBatches(batches, items, sessions, tables)
    }

    /** Một đợt cho nhân viên (sau claim/confirm/reject). */
    @Transactional(readOnly = true)
    fun staffBatch(batchId: Long): StaffBatchResponse {
        val batch = batchRepository.findById(batchId).orElseThrow { ResourceNotFoundException("đợt order", batchId) }
        val items = itemRepository.findAllByBatchIdInOrderByIdAsc(listOf(batchId))
        val session = sessionRepository.findById(batch.sessionId).orElseThrow { ResourceNotFoundException("phiên bàn", batch.sessionId) }
        val table = tableRepository.findById(session.tableId).orElseThrow { ResourceNotFoundException("bàn", session.tableId) }
        return staffBatches(listOf(batch), items, mapOf(batch.sessionId to session), mapOf(requireNotNull(table.id) to table)).single()
    }

    private fun staffBatches(
        batches: List<OrderBatch>,
        allItems: List<OrderItem>,
        sessions: Map<Long, TableSession>,
        tables: Map<Long, DiningTable>,
    ): List<StaffBatchResponse> {
        val ttl = Duration.ofSeconds(settingsService.current().batchClaimTtlSeconds.toLong())
        val now = dbClock.now()
        val itemsByBatch = mapper.itemResponses(allItems).groupBy { it.batchId }
        return batches.map { b ->
            val session = sessions.getValue(b.sessionId)
            val table = tables.getValue(session.tableId)
            val expiresAt = b.claimedAt?.plus(ttl)
            StaffBatchResponse(
                id = requireNotNull(b.id), orderId = b.orderId, sessionId = b.sessionId, tableId = session.tableId,
                tableCode = table.code, seq = b.seq, status = b.status, requiresConfirmation = b.requiresConfirmation,
                submittedAt = b.submittedAt, claimedBy = b.claimedBy, claimedAt = b.claimedAt,
                claimActive = b.status == BatchStatus.PENDING && expiresAt != null && expiresAt.isAfter(now),
                claimExpiresAt = expiresAt, decidedBy = b.decidedBy, decidedAt = b.decidedAt, reasonCode = b.reasonCode,
                items = itemsByBatch[b.id].orEmpty(), version = b.version,
            )
        }
    }

    /** KDS (FR-KIT-01): món CONFIRMED/PREPARING (mặc định) cũ trước; [statuses] lọc theo trạng thái. */
    @Transactional(readOnly = true)
    fun kitchenItems(statuses: Set<OrderItemStatus>): List<KitchenItemResponse> =
        itemsWithContext(itemRepository.findAllByStatusInOrderByConfirmedAtAscIdAsc(statuses))

    /** Món READY chờ mang ra (`/topic/staff/ready`), cũ trước. */
    @Transactional(readOnly = true)
    fun readyItems(): List<KitchenItemResponse> =
        itemsWithContext(itemRepository.findAllByStatusOrderByReadyAtAscIdAsc(OrderItemStatus.READY))

    private fun itemsWithContext(items: List<OrderItem>): List<KitchenItemResponse> {
        if (items.isEmpty()) return emptyList()
        val now = dbClock.now()
        val orders = orderRepository.findAllById(items.map { it.orderId }.distinct()).associateBy { requireNotNull(it.id) }
        val sessions = sessionRepository.findAllById(orders.values.map { it.sessionId }.distinct()).associateBy { requireNotNull(it.id) }
        val tables = tableRepository.findAllById(sessions.values.map { it.tableId }.distinct()).associateBy { requireNotNull(it.id) }
        val options = optionRepository.findAllByOrderItemIdInOrderByIdAsc(items.map { requireNotNull(it.id) }).groupBy { it.orderItemId }
        return items.map { i ->
            val order = orders.getValue(i.orderId)
            val table = tables.getValue(sessions.getValue(order.sessionId).tableId)
            val since = i.confirmedAt ?: i.createdAt ?: now
            KitchenItemResponse(
                itemId = requireNotNull(i.id), orderId = i.orderId, batchId = i.batchId, sessionId = order.sessionId,
                tableCode = table.code, productName = i.productName, quantity = i.quantity, note = i.note,
                options = options[i.id].orEmpty().map { OrderOptionResponse(it.groupName, it.optionName, it.extraPrice) },
                status = i.status, confirmedAt = i.confirmedAt, preparingAt = i.preparingAt, readyAt = i.readyAt,
                waitingSeconds = Duration.between(since, now).seconds.coerceAtLeast(0), version = i.version,
            )
        }
    }

    /** Yêu cầu hủy chờ Manager duyệt (BR-O06), cũ trước. */
    @Transactional(readOnly = true)
    fun pendingCancelRequests(): List<CancelRequestResponse> {
        val requests = cancelRequestRepository.findAllByStatusOrderByRequestedAtAscIdAsc(CancelRequestStatus.PENDING)
        if (requests.isEmpty()) return emptyList()
        val items = itemRepository.findAllById(requests.map { it.orderItemId }).associateBy { requireNotNull(it.id) }
        val orders = orderRepository.findAllById(items.values.map { it.orderId }.distinct()).associateBy { requireNotNull(it.id) }
        val sessions = sessionRepository.findAllById(orders.values.map { it.sessionId }.distinct()).associateBy { requireNotNull(it.id) }
        val tables = tableRepository.findAllById(sessions.values.map { it.tableId }.distinct()).associateBy { requireNotNull(it.id) }
        return requests.map { r ->
            val item = items.getValue(r.orderItemId)
            val order = orders.getValue(item.orderId)
            CancelRequestResponse(
                id = requireNotNull(r.id), orderItemId = r.orderItemId, orderId = item.orderId, sessionId = order.sessionId,
                tableCode = tables.getValue(sessions.getValue(order.sessionId).tableId).code, productName = item.productName,
                quantity = item.quantity, itemStatusAtRequest = r.itemStatusAtRequest, currentItemStatus = item.status,
                status = r.status, requestReason = r.requestReason, requestedAt = r.requestedAt, decidedBy = r.decidedBy,
                decidedAt = r.decidedAt, decisionReason = r.decisionReason, version = r.version,
            )
        }
    }
}
