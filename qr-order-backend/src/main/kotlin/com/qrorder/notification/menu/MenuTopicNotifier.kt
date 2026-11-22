package com.qrorder.notification.menu

import com.qrorder.menu.domain.ProductStatus
import com.qrorder.menu.event.MenuChangeKind
import com.qrorder.menu.event.MenuChangedEvent
import com.qrorder.menu.event.MenuEntityType
import com.qrorder.notification.websocket.StompTopicAuthorizer
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Nội dung gửi tới `/topic/menu` (SRS §11.4). Không chứa thông tin nhân viên (kênh này mọi client nghe được).
 *
 * - [eventId] tăng dần trong một tiến trình backend (khởi tạo bằng epoch millis lúc chạy nên vẫn tăng sau khi
 *   khởi động lại) để client bỏ qua sự kiện cũ/trùng (SRS §11.4).
 * - [version] là version của đối tượng sau thay đổi (optimistic lock).
 * - Client: [MenuChangeKind.STATUS_CHANGED] với PRODUCT có thể cập nhật tại chỗ theo [productStatus];
 *   các loại khác nên tải lại `GET /api/v1/public/menu` (FR-NOT-02 resync).
 */
data class MenuTopicMessage(
    val eventId: Long,
    val type: String,
    val entityType: MenuEntityType,
    val entityId: Long?,
    val change: MenuChangeKind,
    val version: Long?,
    val productStatus: ProductStatus?,
    val price: Long?,
    /** ISO-8601 UTC (chuỗi, không phụ thuộc cấu hình Jackson của message converter). */
    val occurredAt: String,
) {
    companion object {
        const val TYPE = "MENU_CHANGED"
    }
}

/**
 * Đẩy thay đổi menu real-time tới `/topic/menu` SAU KHI transaction commit (SRS §11.2: listener
 * AFTER_COMMIT để không thông báo cho giao dịch bị rollback).
 *
 * Gửi lỗi (broker dừng...) chỉ ghi log cảnh báo: dữ liệu đã commit; client tự resync bằng REST khi reconnect.
 */
@Component
class MenuTopicNotifier(
    private val messagingTemplate: SimpMessagingTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sequence = AtomicLong(clock.millis())

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMenuChanged(event: MenuChangedEvent) {
        val message = MenuTopicMessage(
            eventId = sequence.incrementAndGet(),
            type = MenuTopicMessage.TYPE,
            entityType = event.entityType,
            entityId = event.entityId,
            change = event.change,
            version = event.version,
            productStatus = event.productStatus,
            price = event.price,
            occurredAt = Instant.now(clock).toString(),
        )
        try {
            messagingTemplate.convertAndSend(StompTopicAuthorizer.TOPIC_MENU, message)
        } catch (ex: Exception) {
            log.warn("Không gửi được sự kiện {} tới {}: {}", message.eventId, StompTopicAuthorizer.TOPIC_MENU, ex.message)
        }
    }
}
