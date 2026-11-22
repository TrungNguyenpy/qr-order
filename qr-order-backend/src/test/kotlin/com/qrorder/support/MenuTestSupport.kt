package com.qrorder.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.qrorder.notification.websocket.StompTopicAuthorizer
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.event.ApplicationEventMulticaster
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHandler
import org.springframework.messaging.SubscribableChannel
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ghi lại mọi message gửi tới `/topic/menu` bằng cách subscribe trực tiếp vào `brokerChannel` của Spring
 * (cùng kênh SimpleBroker nhận). Không đổi cấu hình Spring context nên mọi IT vẫn dùng chung context.
 * Kênh broker mặc định gửi đồng bộ trên luồng gọi, nên message đã có ngay khi request/transaction kết thúc.
 */
class MenuTopicRecorder(
    private val brokerChannel: SubscribableChannel,
    private val objectMapper: ObjectMapper,
) : MessageHandler {
    val messages = CopyOnWriteArrayList<JsonNode>()

    fun start(): MenuTopicRecorder = apply {
        messages.clear()
        brokerChannel.subscribe(this)
    }

    fun stop() {
        brokerChannel.unsubscribe(this)
    }

    override fun handleMessage(message: Message<*>) {
        if (SimpMessageHeaderAccessor.getDestination(message.headers) != StompTopicAuthorizer.TOPIC_MENU) return
        messages.add(
            when (val payload = message.payload) {
                is ByteArray -> objectMapper.readTree(payload)
                is String -> objectMapper.readTree(payload)
                else -> objectMapper.valueToTree<JsonNode>(payload)
            },
        )
    }
}

/**
 * Listener gắn/gỡ lúc chạy test (qua ApplicationEventMulticaster) - không đổi Spring context.
 * Ghi lại payload event kiểu [T] và việc event có được phát BÊN TRONG transaction hay không.
 */
class RecordingEventListener<T : Any>(
    private val type: Class<T>,
    private val onEvent: (T) -> Unit = {},
) : ApplicationListener<ApplicationEvent> {
    data class Recorded<T>(val event: T, val insideTransaction: Boolean)

    val events = CopyOnWriteArrayList<Recorded<T>>()

    override fun onApplicationEvent(event: ApplicationEvent) {
        val payload = (event as? PayloadApplicationEvent<*>)?.payload ?: event
        if (type.isInstance(payload)) {
            val typed = type.cast(payload)
            events.add(Recorded(typed, TransactionSynchronizationManager.isActualTransactionActive()))
            onEvent(typed)
        }
    }

    fun register(multicaster: ApplicationEventMulticaster): RecordingEventListener<T> = apply {
        events.clear()
        multicaster.addApplicationListener(this)
    }

    fun unregister(multicaster: ApplicationEventMulticaster) {
        multicaster.removeApplicationListener(this)
    }
}

/** Ảnh mẫu tối thiểu theo chữ ký nhị phân (đủ cho kiểm tra định dạng, không cần là ảnh hợp lệ hoàn chỉnh). */
object TestImages {
    val JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64) { 1 }
    val PNG: ByteArray = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
        ByteArray(64) { 2 }
    val WEBP: ByteArray = "RIFF".toByteArray() + byteArrayOf(0x24, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(64) { 3 }
    val GIF: ByteArray = "GIF89a".toByteArray() + ByteArray(64) { 4 }

    /** JPEG vượt [size] byte. */
    fun jpegOfSize(size: Int): ByteArray = JPEG.copyOf(size).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
}
