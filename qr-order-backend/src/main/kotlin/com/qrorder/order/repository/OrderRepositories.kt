package com.qrorder.order.repository

import com.qrorder.order.domain.BatchStatus
import com.qrorder.order.domain.CancelRequestStatus
import com.qrorder.order.domain.ItemCancelRequest
import com.qrorder.order.domain.Order
import com.qrorder.order.domain.OrderBatch
import com.qrorder.order.domain.OrderItem
import com.qrorder.order.domain.OrderItemOption
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.OrderStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface OrderRepository : JpaRepository<Order, Long> {
    fun findBySessionId(sessionId: Long): Order?

    /** GĐ5b (BR-P17, FR-PAY-13): Order LOCKED chờ Bill thay thế (ix_orders_needs_attention), cũ trước. */
    fun findAllByNeedsAttentionTrueOrderByAttentionSetAtAscIdAsc(): List<Order>

    /** Phiên của Order, đọc dạng giá trị vô hướng để khóa bàn/phiên TRƯỚC rồi mới nạp entity (dữ liệu luôn mới nhất). */
    @Query("select o.sessionId from Orders o where o.id = :id")
    fun findSessionIdById(@Param("id") id: Long): Long?

    /** Phiên có Order OPEN đang chứa món [productId] ở trạng thái [statuses] (BR-O13). */
    @Query(
        """select distinct o.sessionId from Orders o, OrderItem i
            where i.orderId = o.id and o.status = :orderStatus and i.productId = :productId and i.status in :statuses
            order by o.sessionId""",
    )
    fun findSessionIdsWithOpenItemsOfProduct(
        @Param("productId") productId: Long,
        @Param("statuses") statuses: Collection<OrderItemStatus>,
        @Param("orderStatus") orderStatus: OrderStatus,
    ): List<Long>
}

interface OrderBatchRepository : JpaRepository<OrderBatch, Long> {
    fun findBySessionIdAndClientRequestId(sessionId: Long, clientRequestId: UUID): OrderBatch?

    fun findAllByOrderIdOrderBySeqAsc(orderId: Long): List<OrderBatch>

    fun countBySessionIdAndStatus(sessionId: Long, status: BatchStatus): Long

    @Query("select b.orderId from OrderBatch b where b.id = :id")
    fun findOrderIdById(@Param("id") id: Long): Long?

    @Query("select coalesce(max(b.seq), 0) from OrderBatch b where b.orderId = :orderId")
    fun maxSeq(@Param("orderId") orderId: Long): Int

    /** Hàng đợi xác nhận của Staff (ix_order_batch_pending_queue): cũ trước. */
    fun findAllByStatusOrderBySubmittedAtAscIdAsc(status: BatchStatus): List<OrderBatch>

    /**
     * Claim có điều kiện (BR-O10, DATABASE_DESIGN.md §5.3 (8)): chỉ thành công khi đợt còn PENDING và chưa ai giữ,
     * hoặc đã chính [userId] giữ, hoặc claim của người khác đã quá [ttlSeconds] (tính theo đồng hồ DB).
     * [takeover] = true (Manager): giành quyền bất kể claim còn hạn. Trả số dòng cập nhật (0 = thất bại).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """UPDATE order_batch
                      SET claimed_by = :userId, claimed_at = now(), updated_at = now(), version = version + 1
                    WHERE id = :id AND status = 'PENDING'
                      AND (:takeover OR claimed_by IS NULL OR claimed_by = :userId
                           OR claimed_at < now() - (:ttlSeconds * interval '1 second'))""",
        nativeQuery = true,
    )
    fun claim(
        @Param("id") id: Long,
        @Param("userId") userId: Long,
        @Param("ttlSeconds") ttlSeconds: Int,
        @Param("takeover") takeover: Boolean,
    ): Int

    /** Nhả claim: chỉ người đang giữ mới nhả được. Trả số dòng cập nhật. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """UPDATE order_batch SET claimed_by = NULL, claimed_at = NULL, updated_at = now(), version = version + 1
                    WHERE id = :id AND status = 'PENDING' AND claimed_by = :userId""",
        nativeQuery = true,
    )
    fun release(@Param("id") id: Long, @Param("userId") userId: Long): Int
}

interface OrderItemRepository : JpaRepository<OrderItem, Long> {
    fun findAllByOrderIdOrderByIdAsc(orderId: Long): List<OrderItem>

    fun findAllByBatchIdInOrderByIdAsc(batchIds: Collection<Long>): List<OrderItem>

    fun findAllByStatusInOrderByConfirmedAtAscIdAsc(statuses: Collection<OrderItemStatus>): List<OrderItem>

    fun findAllByStatusOrderByReadyAtAscIdAsc(status: OrderItemStatus): List<OrderItem>

    /** BR-O13: món [productId] ở trạng thái [statuses] trong Order [orderId] (ix_order_item_product_open). */
    fun findAllByOrderIdAndProductIdAndStatusInOrderByIdAsc(
        orderId: Long,
        productId: Long,
        statuses: Collection<OrderItemStatus>,
    ): List<OrderItem>

    /** Order của món, đọc dạng giá trị vô hướng (xem [OrderRepository.findSessionIdById]). */
    @Query("select i.orderId from OrderItem i where i.id = :id")
    fun findOrderIdById(@Param("id") id: Long): Long?
}

interface OrderItemOptionRepository : JpaRepository<OrderItemOption, Long> {
    fun findAllByOrderItemIdInOrderByIdAsc(itemIds: Collection<Long>): List<OrderItemOption>
}

interface ItemCancelRequestRepository : JpaRepository<ItemCancelRequest, Long> {
    @Query("select r.orderItemId from ItemCancelRequest r where r.id = :id")
    fun findOrderItemIdById(@Param("id") id: Long): Long?

    fun findFirstByOrderItemIdAndStatus(orderItemId: Long, status: CancelRequestStatus): ItemCancelRequest?

    fun findAllByOrderItemIdInOrderByIdDesc(itemIds: Collection<Long>): List<ItemCancelRequest>

    fun findAllByStatusOrderByRequestedAtAscIdAsc(status: CancelRequestStatus): List<ItemCancelRequest>
}
