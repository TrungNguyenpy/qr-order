package com.qrorder.order.service

import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.repository.UserRepository
import com.qrorder.common.api.ErrorDetail
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.common.settings.RestaurantSettingsService
import com.qrorder.common.time.DbClock
import com.qrorder.order.domain.BatchStatus
import com.qrorder.order.domain.OrderBatch
import com.qrorder.order.domain.OrderItem
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.StatusActor
import com.qrorder.order.dto.ConfirmBatchRequest
import com.qrorder.order.dto.RejectBatchRequest
import com.qrorder.order.dto.StaffBatchResponse
import com.qrorder.order.event.OrderChangeKind
import com.qrorder.order.repository.OrderBatchRepository
import com.qrorder.order.repository.OrderItemRepository
import com.qrorder.order.repository.OrderRepository
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.TableSessionRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/**
 * Staff/Manager xử lý đợt chờ xác nhận (FR-ORD-06, BR-O08, BR-O10, BR-S04).
 *
 * - **claim** (PENDING -> "CLAIMED"): UPDATE có điều kiện theo claimed_by/claimed_at + TTL (batch_claim_ttl_seconds,
 *   mặc định 60s) - nguyên tử, đúng một người thành công; người khác nhận 409 BATCH_CLAIMED_BY_OTHER kèm tên người
 *   đang xử lý. Manager giành quyền được (BR-O10). Database không có trạng thái CLAIMED (không V4): claim là
 *   cặp cột claimed_by/claimed_at, trạng thái đợt vẫn PENDING.
 * - **confirm / reject**: chỉ hợp lệ với người đang giữ claim còn hạn, hoặc khi chưa ai giữ / claim đã hết hạn (khi đó tự nhận
 *   claim); claim còn hạn của người khác -> 409 (trừ Manager). Mọi đổi trạng thái món qua OrderItem.changeStatus (DB-14).
 */
@Service
class OrderBatchService(
    private val locker: SessionOrderLocker,
    private val settingsService: RestaurantSettingsService,
    private val orderRepository: OrderRepository,
    private val batchRepository: OrderBatchRepository,
    private val itemRepository: OrderItemRepository,
    private val userRepository: UserRepository,
    private val sessionRepository: TableSessionRepository,
    private val tableRepository: DiningTableRepository,
    private val synchronizer: BatchStatusSynchronizer,
    private val queryService: OrderQueryService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun claim(userId: Long, role: UserRole, batchId: Long): StaffBatchResponse {
        val batch = batchRepository.findById(batchId).orElseThrow { ResourceNotFoundException("đợt order", batchId) }
        if (batch.status != BatchStatus.PENDING) throw BusinessException(ErrorCode.BATCH_NOT_PENDING)
        val ttl = settingsService.current().batchClaimTtlSeconds
        val updated = batchRepository.claim(batchId, userId, ttl, takeover = role == UserRole.MANAGER)
        if (updated == 0) {
            val current = batchRepository.findById(batchId).orElseThrow { ResourceNotFoundException("đợt order", batchId) }
            if (current.status != BatchStatus.PENDING) throw BusinessException(ErrorCode.BATCH_NOT_PENDING)
            throw claimedByOther(current)
        }
        publishClaimChanged(batchId)
        return queryService.staffBatch(batchId)
    }

    @Transactional
    fun release(userId: Long, batchId: Long): StaffBatchResponse {
        val batch = batchRepository.findById(batchId).orElseThrow { ResourceNotFoundException("đợt order", batchId) }
        if (batch.status != BatchStatus.PENDING) throw BusinessException(ErrorCode.BATCH_NOT_PENDING)
        if (batchRepository.release(batchId, userId) == 0) throw BusinessException(ErrorCode.BATCH_CLAIMED_BY_OTHER, "Bạn không giữ claim của đợt này")
        publishClaimChanged(batchId)
        return queryService.staffBatch(batchId)
    }

    /** Xác nhận đợt; [ConfirmBatchRequest.rejectItems] từ chối một phần (đợt -> PARTIALLY_ACCEPTED, hoặc REJECTED nếu từ chối hết). */
    @Transactional
    fun confirm(userId: Long, role: UserRole, batchId: Long, request: ConfirmBatchRequest): StaffBatchResponse {
        val rejects = request.rejectItems
        val ids = rejects.map { requireNotNull(it.itemId) }
        if (ids.size != ids.toSet().size) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, details = listOf(ErrorDetail("rejectItems", "Món bị từ chối bị trùng")))
        }
        return decide(userId, role, batchId) { items, now, actor ->
            val byId = items.associateBy { it.id }
            rejects.forEach { r ->
                val item = byId[r.itemId] ?: throw BusinessException(
                    ErrorCode.VALIDATION_FAILED, details = listOf(ErrorDetail("rejectItems", "Món #${r.itemId} không thuộc đợt")),
                )
                if (item.status != OrderItemStatus.PENDING) {
                    throw BusinessException(ErrorCode.ORDER_ITEM_STATE_INVALID, "Món #${item.id} đang ${item.status}, không thể từ chối")
                }
                item.changeStatus(OrderItemStatus.REJECTED, actor, now, r.reasonCode, r.reasonNote)
            }
            items.filter { it.status == OrderItemStatus.PENDING }.forEach { it.changeStatus(OrderItemStatus.CONFIRMED, actor, now) }
            null
        }
    }

    /** Từ chối cả đợt: mọi món PENDING -> REJECTED, đợt REJECTED (BR-O08: reasonCode bắt buộc). */
    @Transactional
    fun reject(userId: Long, role: UserRole, batchId: Long, request: RejectBatchRequest): StaffBatchResponse =
        decide(userId, role, batchId) { items, now, actor ->
            items.filter { it.status == OrderItemStatus.PENDING }
                .forEach { it.changeStatus(OrderItemStatus.REJECTED, actor, now, request.reasonCode, request.reasonNote) }
            request
        }

    private fun decide(
        userId: Long,
        role: UserRole,
        batchId: Long,
        apply: (List<OrderItem>, java.time.OffsetDateTime, StatusActor) -> RejectBatchRequest?,
    ): StaffBatchResponse {
        val orderId = batchRepository.findOrderIdById(batchId) ?: throw ResourceNotFoundException("đợt order", batchId)
        val sessionId = orderRepository.findSessionIdById(orderId) ?: throw ResourceNotFoundException("order", orderId)
        val locked = locker.lock(sessionId)
        val batch = batchRepository.findById(batchId).orElseThrow { ResourceNotFoundException("đợt order", batchId) }
        if (batch.status != BatchStatus.PENDING) throw BusinessException(ErrorCode.BATCH_NOT_PENDING)
        val order = orderRepository.findById(orderId).orElseThrow { ResourceNotFoundException("order", orderId) }
        OrderRules.requireOpen(order)
        val now = dbClock.now()
        requireClaimUsable(batch, userId, role, now)

        val items = itemRepository.findAllByBatchIdInOrderByIdAsc(listOf(batchId))
        val pendingBefore = items.filter { it.status == OrderItemStatus.PENDING }
        if (pendingBefore.isEmpty()) throw BusinessException(ErrorCode.BATCH_NOT_PENDING)
        val actor = StatusActor.Staff(userId)
        val wholeReject = apply(items, now, actor)
        itemRepository.saveAllAndFlush(items)

        val confirmedNow = pendingBefore.count { it.status == OrderItemStatus.CONFIRMED }
        val rejectedAny = items.any { it.status == OrderItemStatus.REJECTED }
        batch.decidedBy = userId
        batch.decidedAt = now
        batch.status = when {
            confirmedNow == 0 -> BatchStatus.REJECTED
            rejectedAny -> BatchStatus.PARTIALLY_ACCEPTED
            else -> BatchStatus.ACCEPTED
        }
        if (batch.status == BatchStatus.REJECTED) {
            batch.reasonCode = wholeReject?.reasonCode ?: items.firstNotNullOfOrNull { it.reasonCode }
            batch.reasonNote = wholeReject?.reasonNote
        }
        // BR-S04: đợt đầu tiên được Staff chấp nhận xác minh phiên (FIRST_BATCH_ONLY dựa vào mốc này).
        if (confirmedNow > 0 && locked.session.verifiedAt == null) {
            locked.session.verifiedAt = now
            locker.save(locked.session)
        }
        batchRepository.saveAndFlush(batch)
        synchronizer.sync(batch, items, now)

        events.publishEvent(
            OrderRules.event(OrderChangeKind.BATCH_DECIDED, locked, order, batchId, items, kitchen = confirmedNow > 0, staff = true),
        )
        return queryService.staffBatch(batchId)
    }

    /** BR-O10: claim còn hạn của người khác chặn (trừ Manager); claim của chính mình, hết hạn hoặc không có thì cho qua. */
    private fun requireClaimUsable(batch: OrderBatch, userId: Long, role: UserRole, now: java.time.OffsetDateTime) {
        val claimedBy = batch.claimedBy ?: return
        if (claimedBy == userId || role == UserRole.MANAGER) return
        val ttl = Duration.ofSeconds(settingsService.current().batchClaimTtlSeconds.toLong())
        val claimedAt = batch.claimedAt ?: return
        if (claimedAt.plus(ttl).isAfter(now)) throw claimedByOther(batch)
    }

    private fun claimedByOther(batch: OrderBatch): BusinessException {
        val name = batch.claimedBy?.let { userRepository.findById(it).orElse(null)?.fullName }
        val message = if (name != null) "Đợt order đang được $name xử lý" else ErrorCode.BATCH_CLAIMED_BY_OTHER.defaultMessage
        return BusinessException(ErrorCode.BATCH_CLAIMED_BY_OTHER, message)
    }

    /**
     * Thông báo claim đổi. KHÔNG khóa bàn/phiên: claim đang giữ khóa dòng order_batch (UPDATE có điều kiện), nếu còn
     * xin khóa bàn sẽ ngược thứ tự khóa (bàn -> phiên -> đợt) của confirm/reject và gây deadlock.
     */
    private fun publishClaimChanged(batchId: Long) {
        val orderId = batchRepository.findOrderIdById(batchId) ?: return
        val order = orderRepository.findById(orderId).orElse(null) ?: return
        val tableId = sessionRepository.findTableIdById(order.sessionId) ?: return
        val table = tableRepository.findById(tableId).orElse(null) ?: return
        val items = itemRepository.findAllByBatchIdInOrderByIdAsc(listOf(batchId))
        events.publishEvent(
            OrderRules.event(OrderChangeKind.BATCH_CLAIM_CHANGED, order.sessionId, tableId, table.code, order, batchId, items, staff = true),
        )
    }
}
