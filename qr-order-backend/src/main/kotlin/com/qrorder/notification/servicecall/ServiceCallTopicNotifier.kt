package com.qrorder.notification.servicecall

import com.qrorder.notification.domain.ServiceCallStatus
import com.qrorder.notification.domain.ServiceCallType
import com.qrorder.notification.table.TableTopicNotifier
import com.qrorder.notification.websocket.StompTopicAuthorizer
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Thông báo ServiceCall (không chứa ghi chú hay thông tin nhân viên; client tải chi tiết bằng REST). */
data class ServiceCallTopicMessage(
    val eventId: Long,
    val type: String,
    val change: ServiceCallChangeKind,
    val callId: Long,
    val callType: ServiceCallType,
    val status: ServiceCallStatus,
    val autoResolved: Boolean,
    val sessionId: Long,
    val tableId: Long,
    val tableCode: String,
    val occurredAt: String,
) {
    companion object {
        const val TYPE = "SERVICE_CALL_CHANGED"
    }
}

/**
 * FR-NOT-01/03: đẩy SAU COMMIT tới `/topic/staff/orders` (mọi loại) và `/topic/session/{sessionId}` (CALL_STAFF,
 * REQUEST_PAYMENT - khách thấy yêu cầu của mình được nhận/xử lý). MANAGER_SUPPORT là trao đổi nội bộ Staff - Manager
 * nên không gửi lên kênh phiên (kênh khách nghe). Gửi lỗi chỉ ghi cảnh báo; client resync bằng REST.
 */
@Component
class ServiceCallTopicNotifier(
    private val messagingTemplate: SimpMessagingTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sequence = AtomicLong(clock.millis())

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onServiceCallChanged(event: ServiceCallChangedEvent) {
        val message = ServiceCallTopicMessage(
            eventId = sequence.incrementAndGet(), type = ServiceCallTopicMessage.TYPE, change = event.kind, callId = event.callId,
            callType = event.type, status = event.status, autoResolved = event.autoResolved, sessionId = event.sessionId,
            tableId = event.tableId, tableCode = event.tableCode, occurredAt = Instant.now(clock).toString(),
        )
        send(StompTopicAuthorizer.TOPIC_STAFF_ORDERS, message)
        if (event.type != ServiceCallType.MANAGER_SUPPORT) send(TableTopicNotifier.sessionTopic(event.sessionId), message)
    }

    private fun send(destination: String, payload: Any) {
        try {
            messagingTemplate.convertAndSend(destination, payload)
        } catch (ex: Exception) {
            log.warn("Không gửi được sự kiện tới {}: {}", destination, ex.message)
        }
    }
}
