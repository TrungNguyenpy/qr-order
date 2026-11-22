package com.qrorder.order.service

import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.common.time.DbClock
import com.qrorder.order.domain.BatchStatus
import com.qrorder.order.domain.CancelRequestStatus
import com.qrorder.order.domain.ItemCancelRequest
import com.qrorder.order.domain.Order
import com.qrorder.order.domain.OrderBatch
import com.qrorder.order.domain.OrderItem
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.OrderStatus
import com.qrorder.order.dto.CancelRequestSummary
import com.qrorder.order.dto.OrderBatchResponse
import com.qrorder.order.dto.OrderItemResponse
import com.qrorder.order.dto.OrderOptionResponse
import com.qrorder.order.event.OrderChangeKind
import com.qrorder.order.event.OrderChangedEvent
import com.qrorder.order.event.OrderItemSnapshot
import com.qrorder.order.repository.ItemCancelRequestRepository
import com.qrorder.order.repository.OrderBatchRepository
import com.qrorder.order.repository.OrderItemOptionRepository
import com.qrorder.order.repository.OrderItemRepository
import com.qrorder.table.domain.DiningTable
import com.qrorder.table.domain.TableSession
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.TableSessionRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/** Bàn + phiên đã được khóa FOR UPDATE (bàn trước, phiên sau). */
class LockedSession(val table: DiningTable, val session: TableSession) {
    val sessionId: Long get() = requireNotNull(session.id)
    val tableId: Long get() = requireNotNull(table.id)
}

/**
 * Khóa dòng bàn rồi dòng phiên (FOR UPDATE) - CÙNG thứ tự với join, force-close và Scheduler của module `table`
 * (DATABASE_DESIGN.md §5.3 (2)) nên không deadlock, đồng thời tuần tự hóa mọi thay đổi order của một bàn với
 * force-close, đổi trạng thái phiên và (GĐ5) tạo Bill. Mọi thao tác GHI của module order đều đi qua đây.
 */
@Component
class SessionOrderLocker(
    private val tableRepository: DiningTableRepository,
    private val sessionRepository: TableSessionRepository,
) {
    fun lock(sessionId: Long): LockedSession {
        val tableId = sessionRepository.findTableIdById(sessionId) ?: throw ResourceNotFoundException("phiên bàn", sessionId)
        val table = tableRepository.findByIdForUpdate(tableId) ?: throw ResourceNotFoundException("bàn", tableId)
        val session = sessionRepository.findByIdForUpdate(sessionId) ?: throw ResourceNotFoundException("phiên bàn", sessionId)
        return LockedSession(table, session)
    }

    /** Ghi thay đổi của phiên đã khóa (ví dụ last_activity_at, verified_at). */
    fun save(session: TableSession): TableSession = sessionRepository.saveAndFlush(session)
}

/** Đồng bộ trạng thái đợt theo trạng thái các món của đợt (sau khi món đổi trạng thái). */
@Component
class BatchStatusSynchronizer(private val dbClock: DbClock) {
    /**
     * - Đợt PENDING không còn món PENDING (khách hủy hết / hệ thống từ chối hết): CANCELLED nếu có món bị khách hủy,
     *   ngược lại REJECTED (reasonCode lấy từ món đầu tiên bị từ chối).
     * - Đợt ACCEPTED/PARTIALLY_ACCEPTED mà mọi món đã ở trạng thái cuối: COMPLETED nếu có món SERVED; nếu không món nào
     *   được phục vụ thì CANCELLED/REJECTED như trên.
     * Trả true nếu trạng thái đợt đổi (caller lưu).
     */
    fun sync(batch: OrderBatch, items: List<OrderItem>, now: OffsetDateTime = dbClock.now()): Boolean {
        if (items.isEmpty()) return false
        val before = batch.status
        when (batch.status) {
            BatchStatus.PENDING -> if (items.none { it.status == OrderItemStatus.PENDING }) closeBatch(batch, items, now)
            BatchStatus.ACCEPTED, BatchStatus.PARTIALLY_ACCEPTED -> if (items.all { it.status.isFinal }) {
                if (items.any { it.status == OrderItemStatus.SERVED }) {
                    batch.status = BatchStatus.COMPLETED
                    batch.completedAt = now
                } else {
                    closeBatch(batch, items, now)
                }
            }
            else -> Unit
        }
        return batch.status != before
    }

    private fun closeBatch(batch: OrderBatch, items: List<OrderItem>, now: OffsetDateTime) {
        val hasGuestCancel = items.any { it.status == OrderItemStatus.CANCELLED }
        if (hasGuestCancel) {
            batch.status = BatchStatus.CANCELLED
        } else {
            batch.status = BatchStatus.REJECTED
            batch.reasonCode = batch.reasonCode ?: items.firstNotNullOfOrNull { it.reasonCode }
        }
        if (batch.decidedAt == null) batch.decidedAt = now
    }
}

/** Dựng phản hồi (tải options, yêu cầu hủy theo lô - không N+1). */
@Component
class OrderViewMapper(
    private val batchRepository: OrderBatchRepository,
    private val itemRepository: OrderItemRepository,
    private val optionRepository: OrderItemOptionRepository,
    private val cancelRequestRepository: ItemCancelRequestRepository,
) {
    fun itemResponses(items: List<OrderItem>): List<OrderItemResponse> {
        if (items.isEmpty()) return emptyList()
        val ids = items.map { requireNotNull(it.id) }
        val options = optionRepository.findAllByOrderItemIdInOrderByIdAsc(ids).groupBy { it.orderItemId }
        val requests = cancelRequestRepository.findAllByOrderItemIdInOrderByIdDesc(ids)
            .groupBy { it.orderItemId }.mapValues { it.value.first() }
        return items.map { toResponse(it, options[it.id].orEmpty().map { o -> OrderOptionResponse(o.groupName, o.optionName, o.extraPrice) }, requests[it.id]) }
    }

    fun batchResponses(orderId: Long): List<OrderBatchResponse> {
        val batches = batchRepository.findAllByOrderIdOrderBySeqAsc(orderId)
        return batchResponses(batches, itemRepository.findAllByOrderIdOrderByIdAsc(orderId))
    }

    fun batchResponses(batches: List<OrderBatch>, allItems: List<OrderItem>): List<OrderBatchResponse> {
        val itemResponses = itemResponses(allItems).groupBy { it.batchId }
        return batches.map { b ->
            OrderBatchResponse(
                id = requireNotNull(b.id), seq = b.seq, status = b.status, requiresConfirmation = b.requiresConfirmation,
                submittedAt = b.submittedAt, decidedAt = b.decidedAt, reasonCode = b.reasonCode,
                items = itemResponses[b.id].orEmpty(), version = b.version,
            )
        }
    }

    fun toResponse(i: OrderItem, options: List<OrderOptionResponse>, request: ItemCancelRequest?) = OrderItemResponse(
        id = requireNotNull(i.id), batchId = i.batchId, productId = i.productId, productName = i.productName,
        unitPrice = i.unitPrice, optionExtraPerUnit = i.optionExtraPerUnit, quantity = i.quantity, lineTotal = i.lineTotal,
        note = i.note, status = i.status, reasonCode = i.reasonCode, wasted = i.wasted, options = options,
        cancelRequest = request?.let { CancelRequestSummary(requireNotNull(it.id), it.status, it.requestedAt) },
        createdAt = i.createdAt, confirmedAt = i.confirmedAt, preparingAt = i.preparingAt, readyAt = i.readyAt,
        servedAt = i.servedAt, closedAt = i.closedAt, version = i.version,
    )
}

/** Hàm dùng chung: dựng sự kiện, kiểm tra Order OPEN (BR-O12), tự từ chối yêu cầu hủy treo. */
internal object OrderRules {
    fun requireOpen(order: Order) {
        if (order.status != OrderStatus.OPEN) {
            throw BusinessException(ErrorCode.ORDER_NOT_OPEN, "Order đang ${order.status}, không thể hủy, từ chối hay sửa món")
        }
    }

    fun snapshot(item: OrderItem) =
        OrderItemSnapshot(requireNotNull(item.id), item.productName, item.quantity, item.status, item.reasonCode)

    fun event(
        kind: OrderChangeKind,
        locked: LockedSession,
        order: Order,
        batchId: Long?,
        items: List<OrderItem>,
        kitchen: Boolean = false,
        staff: Boolean = false,
        ready: Boolean = false,
    ) = event(kind, locked.sessionId, locked.tableId, locked.table.code, order, batchId, items, kitchen, staff, ready)

    fun event(
        kind: OrderChangeKind,
        sessionId: Long,
        tableId: Long,
        tableCode: String,
        order: Order,
        batchId: Long?,
        items: List<OrderItem>,
        kitchen: Boolean = false,
        staff: Boolean = false,
        ready: Boolean = false,
    ) = OrderChangedEvent(
        kind = kind, sessionId = sessionId, tableId = tableId, tableCode = tableCode,
        orderId = requireNotNull(order.id), batchId = batchId, items = items.map(::snapshot),
        kitchenRelevant = kitchen, staffRelevant = staff, readyRelevant = ready,
    )

    /** Yêu cầu hủy PENDING của món bị đóng bởi đường khác: DENIED tự động để không treo (người xử lý ghi nhận). */
    fun denyPendingRequest(
        repository: ItemCancelRequestRepository,
        itemId: Long,
        actorUserId: Long,
        reason: String,
        now: OffsetDateTime,
    ) {
        val pending = repository.findFirstByOrderItemIdAndStatus(itemId, CancelRequestStatus.PENDING) ?: return
        pending.status = CancelRequestStatus.DENIED
        pending.decidedBy = actorUserId
        pending.decidedAt = now
        pending.decisionReason = reason
        repository.save(pending)
    }
}
