package com.qrorder.order.listener

import com.qrorder.common.time.DbClock
import com.qrorder.menu.event.ProductSoldOutEvent
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.OrderStatus
import com.qrorder.order.domain.StatusActor
import com.qrorder.order.event.OrderChangeKind
import com.qrorder.order.repository.OrderBatchRepository
import com.qrorder.order.repository.OrderItemRepository
import com.qrorder.order.repository.OrderRepository
import com.qrorder.order.service.BatchStatusSynchronizer
import com.qrorder.order.service.OrderReasonCodes
import com.qrorder.order.service.OrderRules
import com.qrorder.order.service.SessionOrderLocker
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * BR-O13: món chuyển SOLD_OUT thì item PENDING và CONFIRMED của món đó trong các Order **OPEN** tự động REJECTED
 * (`OUT_OF_STOCK`, người thực hiện SYSTEM - BR-O09). Order LOCKED (đang thanh toán) giữ nguyên (BR-O12).
 *
 * Listener ĐỒNG BỘ (không phải AFTER_COMMIT): chạy trong CHÍNH transaction đổi trạng thái món (MenuEventsIT đã khẳng định
 * ProductSoldOutEvent phát bên trong transaction), nên món hết hàng và các dòng order bị từ chối cùng commit hoặc cùng rollback.
 * Thông báo real-time vẫn chỉ gửi sau commit (OrderTopicNotifier).
 *
 * Khóa: theo từng phiên (tăng dần theo sessionId để thống nhất thứ tự khóa) -> khóa bàn + phiên, rồi nạp lại món để tránh
 * dữ liệu cũ. Món PENDING bị từ chối hết thì đợt chuyển REJECTED (BatchStatusSynchronizer).
 */
@Component
class ProductSoldOutOrderListener(
    private val locker: SessionOrderLocker,
    private val orderRepository: OrderRepository,
    private val batchRepository: OrderBatchRepository,
    private val itemRepository: OrderItemRepository,
    private val synchronizer: BatchStatusSynchronizer,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun onProductSoldOut(event: ProductSoldOutEvent) {
        val statuses = setOf(OrderItemStatus.PENDING, OrderItemStatus.CONFIRMED)
        val sessionIds = orderRepository.findSessionIdsWithOpenItemsOfProduct(event.productId, statuses, OrderStatus.OPEN)
        sessionIds.forEach { sessionId ->
            val locked = locker.lock(sessionId)
            val order = orderRepository.findBySessionId(sessionId) ?: return@forEach
            if (order.status != OrderStatus.OPEN) return@forEach
            val items = itemRepository.findAllByOrderIdAndProductIdAndStatusInOrderByIdAsc(requireNotNull(order.id), event.productId, statuses)
            if (items.isEmpty()) return@forEach
            val now = dbClock.now()
            val hadConfirmed = items.any { it.status == OrderItemStatus.CONFIRMED }
            items.forEach { it.changeStatus(OrderItemStatus.REJECTED, StatusActor.System, now, OrderReasonCodes.OUT_OF_STOCK) }
            itemRepository.saveAllAndFlush(items)
            items.map { it.batchId }.distinct().forEach { batchId ->
                val batch = batchRepository.findById(batchId).orElse(null) ?: return@forEach
                val batchItems = itemRepository.findAllByBatchIdInOrderByIdAsc(listOf(batchId))
                if (synchronizer.sync(batch, batchItems, now)) batchRepository.saveAndFlush(batch)
            }
            events.publishEvent(
                OrderRules.event(OrderChangeKind.SYSTEM_REJECTED, locked, order, null, items, kitchen = hadConfirmed, staff = true),
            )
            log.info("SOLD_OUT món {}: tự từ chối {} dòng của phiên {}", event.productId, items.size, sessionId)
        }
    }
}
