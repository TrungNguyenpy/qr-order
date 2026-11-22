package com.qrorder.notification.billing

import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.domain.PaymentStatus
import com.qrorder.billing.event.BillChangeKind
import com.qrorder.billing.event.BillChangedEvent
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

/** Thông báo Bill/thanh toán: chỉ số liệu tổng hợp (khách cùng nghe kênh phiên). */
data class BillTopicMessage(
    val eventId: Long,
    val type: String,
    val change: BillChangeKind,
    val sessionId: Long,
    val tableId: Long,
    val tableCode: String,
    val orderId: Long,
    val billId: Long,
    val billNumber: String,
    val billStatus: BillStatus,
    val paymentStatus: PaymentStatus,
    val totalAmount: Long,
    val paidAmount: Long,
    val remainingAmount: Long,
    val occurredAt: String,
) {
    companion object {
        const val TYPE = "BILL_CHANGED"
    }
}

/**
 * SRS §6.8: phiếu tạm tính / trạng thái thanh toán tới `/topic/session/{sessionId}` (khách + nhân viên) và
 * `/topic/staff/orders` (thu ngân, Manager). Gửi SAU COMMIT; lỗi gửi chỉ ghi cảnh báo, client resync bằng REST
 * (`GET /api/v1/guest/bill`, `GET /api/v1/orders/{orderId}/bill`).
 */
@Component
class BillingTopicNotifier(
    private val messagingTemplate: SimpMessagingTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sequence = AtomicLong(clock.millis())

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onBillChanged(event: BillChangedEvent) {
        val message = BillTopicMessage(
            eventId = sequence.incrementAndGet(), type = BillTopicMessage.TYPE, change = event.kind, sessionId = event.sessionId,
            tableId = event.tableId, tableCode = event.tableCode, orderId = event.orderId, billId = event.billId,
            billNumber = event.billNumber, billStatus = event.billStatus, paymentStatus = event.paymentStatus,
            totalAmount = event.totalAmount, paidAmount = event.paidAmount, remainingAmount = event.remainingAmount,
            occurredAt = Instant.now(clock).toString(),
        )
        send(TableTopicNotifier.sessionTopic(event.sessionId), message)
        send(StompTopicAuthorizer.TOPIC_STAFF_ORDERS, message)
    }

    private fun send(destination: String, payload: Any) {
        try {
            messagingTemplate.convertAndSend(destination, payload)
        } catch (ex: Exception) {
            log.warn("Không gửi được sự kiện tới {}: {}", destination, ex.message)
        }
    }
}
