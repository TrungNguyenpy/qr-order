package com.qrorder.notification.table

import com.qrorder.notification.websocket.StompTopicAuthorizer
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.domain.TableStatus
import com.qrorder.table.event.SessionChangeKind
import com.qrorder.table.event.SessionChangedEvent
import com.qrorder.table.event.TableChangeKind
import com.qrorder.table.event.TableChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** `/topic/tables` (STAFF, MANAGER): sơ đồ bàn real-time (FR-TBL-02). */
data class TableTopicMessage(
    val eventId: Long,
    val type: String,
    val tableId: Long,
    val code: String,
    val status: TableStatus,
    val active: Boolean,
    val version: Long,
    val change: TableChangeKind,
    val sessionId: Long?,
    val occurredAt: String,
) {
    companion object {
        const val TYPE = "TABLE_CHANGED"
    }
}

/**
 * `/topic/session/{sessionId}` (khách của phiên + STAFF/MANAGER) và `/topic/staff/orders` (cần xử lý).
 * Khách nhận [SessionChangeKind.CLOSED] thì dừng thao tác, ngắt WebSocket (Guest JWT đã bị từ chối - BE-05).
 * Không chứa thông tin nhân viên.
 */
data class SessionTopicMessage(
    val eventId: Long,
    val type: String,
    val sessionId: Long,
    val tableId: Long,
    val tableCode: String,
    val status: SessionStatus,
    val version: Long,
    val change: SessionChangeKind,
    val occurredAt: String,
) {
    companion object {
        const val TYPE_SESSION = "SESSION_CHANGED"
        const val TYPE_ATTENTION = "SESSION_NEEDS_ATTENTION"
    }
}

/**
 * Đẩy thay đổi bàn/phiên SAU KHI transaction commit (SRS §11.2, §11.4). eventId tăng dần trong tiến trình (khởi tạo
 * bằng epoch millis) để client bỏ qua sự kiện cũ/trùng. Gửi lỗi chỉ ghi cảnh báo: client resync bằng REST
 * (`GET /api/v1/tables/floor-plan`, `GET /api/v1/guest/session`).
 */
@Component
class TableTopicNotifier(
    private val messagingTemplate: SimpMessagingTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sequence = AtomicLong(clock.millis())

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onTableChanged(event: TableChangedEvent) {
        send(
            StompTopicAuthorizer.TOPIC_TABLES,
            TableTopicMessage(
                eventId = sequence.incrementAndGet(),
                type = TableTopicMessage.TYPE,
                tableId = event.tableId,
                code = event.code,
                status = event.status,
                active = event.active,
                version = event.version,
                change = event.change,
                sessionId = event.sessionId,
                occurredAt = Instant.now(clock).toString(),
            ),
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onSessionChanged(event: SessionChangedEvent) {
        val attention = event.change == SessionChangeKind.NEEDS_ATTENTION
        val message = SessionTopicMessage(
            eventId = sequence.incrementAndGet(),
            type = if (attention) SessionTopicMessage.TYPE_ATTENTION else SessionTopicMessage.TYPE_SESSION,
            sessionId = event.sessionId,
            tableId = event.tableId,
            tableCode = event.tableCode,
            status = event.status,
            version = event.version,
            change = event.change,
            occurredAt = Instant.now(clock).toString(),
        )
        if (attention) {
            // FR-SES-06: thông báo Staff/Manager; khách không cần biết phiên bị gắn cờ.
            send(StompTopicAuthorizer.TOPIC_STAFF_ORDERS, message)
        } else {
            send(sessionTopic(event.sessionId), message)
        }
    }

    private fun send(destination: String, payload: Any) {
        try {
            messagingTemplate.convertAndSend(destination, payload)
        } catch (ex: Exception) {
            log.warn("Không gửi được sự kiện tới {}: {}", destination, ex.message)
        }
    }

    companion object {
        fun sessionTopic(sessionId: Long) = "/topic/session/$sessionId"
    }
}
