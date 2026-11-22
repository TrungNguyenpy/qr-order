package com.qrorder.notification.order

import com.qrorder.notification.table.TableTopicNotifier
import com.qrorder.notification.websocket.StompTopicAuthorizer
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.event.OrderChangeKind
import com.qrorder.order.event.OrderChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Một món trong thông báo. Không chứa thông tin nhân viên (kênh phiên có khách nghe). */
data class OrderTopicItem(
    val itemId: Long,
    val productName: String,
    val quantity: Int,
    val status: OrderItemStatus,
    val reasonCode: String?,
)

/**
 * Thông báo order/đợt/món real-time (SRS §11.2, §11.4):
 * - `/topic/session/{sessionId}`: mọi thay đổi (khách của phiên + STAFF/MANAGER).
 * - `/topic/kitchen/orders`: món vào/ra danh sách bếp (KITCHEN/MANAGER).
 * - `/topic/staff/orders`: đợt mới/đợt cần xử lý/yêu cầu hủy (STAFF/MANAGER).
 * - `/topic/staff/ready`: món READY chờ mang ra hoặc vừa được phục vụ (STAFF/MANAGER).
 */
data class OrderTopicMessage(
    val eventId: Long,
    val type: String,
    val change: OrderChangeKind,
    val sessionId: Long,
    val tableId: Long,
    val tableCode: String,
    val orderId: Long,
    val batchId: Long?,
    val items: List<OrderTopicItem>,
    val occurredAt: String,
) {
    companion object {
        const val TYPE = "ORDER_CHANGED"
    }
}

/**
 * Gửi SAU KHI transaction commit (AFTER_COMMIT): rollback thì không có thông báo, không bao giờ lộ trạng thái chưa commit.
 * Gửi lỗi chỉ ghi cảnh báo - client resync bằng REST (`GET /api/v1/guest/orders`, `/api/v1/kitchen/items`, ...).
 */
@Component
class OrderTopicNotifier(
    private val messagingTemplate: SimpMessagingTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sequence = AtomicLong(clock.millis())

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOrderChanged(event: OrderChangedEvent) {
        val message = OrderTopicMessage(
            eventId = sequence.incrementAndGet(), type = OrderTopicMessage.TYPE, change = event.kind,
            sessionId = event.sessionId, tableId = event.tableId, tableCode = event.tableCode, orderId = event.orderId,
            batchId = event.batchId,
            items = event.items.map { OrderTopicItem(it.itemId, it.productName, it.quantity, it.status, it.reasonCode) },
            occurredAt = Instant.now(clock).toString(),
        )
        send(TableTopicNotifier.sessionTopic(event.sessionId), message)
        if (event.kitchenRelevant) send(StompTopicAuthorizer.TOPIC_KITCHEN_ORDERS, message)
        if (event.staffRelevant) send(StompTopicAuthorizer.TOPIC_STAFF_ORDERS, message)
        if (event.readyRelevant) send(StompTopicAuthorizer.TOPIC_STAFF_READY, message)
    }

    private fun send(destination: String, payload: Any) {
        try {
            messagingTemplate.convertAndSend(destination, payload)
        } catch (ex: Exception) {
            log.warn("Không gửi được sự kiện tới {}: {}", destination, ex.message)
        }
    }
}
