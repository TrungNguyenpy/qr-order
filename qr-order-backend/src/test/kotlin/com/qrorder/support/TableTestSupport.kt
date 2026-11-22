package com.qrorder.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.table.session.SessionForceCloseContext
import com.qrorder.table.session.SessionForceCloseListener
import com.qrorder.table.session.SessionForceCloseValidator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHandler
import org.springframework.messaging.SubscribableChannel
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ghi lại mọi message STOMP gửi tới các topic (subscribe trực tiếp `brokerChannel`, như [MenuTopicRecorder] nhưng
 * không lọc topic). Kênh broker gửi đồng bộ nên message có ngay khi request/transaction kết thúc.
 */
class StompTopicRecorder(
    private val brokerChannel: SubscribableChannel,
    private val objectMapper: ObjectMapper,
) : MessageHandler {
    data class Sent(val destination: String, val payload: JsonNode)

    val sent = CopyOnWriteArrayList<Sent>()

    fun start(): StompTopicRecorder = apply {
        sent.clear()
        brokerChannel.subscribe(this)
    }

    fun stop() {
        brokerChannel.unsubscribe(this)
    }

    /** Xóa các tin đã ghi (để kiểm tra chỉ những tin phát sinh sau một bước dựng dữ liệu). */
    fun clear() {
        sent.clear()
    }

    fun on(destination: String): List<JsonNode> = sent.filter { it.destination == destination }.map { it.payload }

    override fun handleMessage(message: Message<*>) {
        val destination = SimpMessageHeaderAccessor.getDestination(message.headers) ?: return
        val payload = when (val p = message.payload) {
            is ByteArray -> objectMapper.readTree(p)
            is String -> objectMapper.readTree(p)
            else -> objectMapper.valueToTree<JsonNode>(p)
        }
        sent.add(Sent(destination, payload))
    }
}

/**
 * Validator + listener force-close dùng trong test (điểm mở rộng cho GĐ4-GĐ6). Mặc định tắt (không làm gì).
 * [rejectWith] khác null: validator từ chối với mã đó. Ghi lại ngữ cảnh và việc chạy trong transaction.
 */
class SwitchableForceCloseHooks : SessionForceCloseValidator, SessionForceCloseListener {
    data class Call(val phase: String, val context: SessionForceCloseContext, val insideTransaction: Boolean)

    @Volatile var enabled: Boolean = false
    @Volatile var rejectWith: ErrorCode? = null
    val calls = CopyOnWriteArrayList<Call>()

    fun reset() {
        enabled = false
        rejectWith = null
        calls.clear()
    }

    override fun validate(context: SessionForceCloseContext) {
        if (!enabled) return
        calls.add(Call("validate", context, TransactionSynchronizationManager.isActualTransactionActive()))
        rejectWith?.let { throw BusinessException(it, "Bị chặn bởi validator thử nghiệm") }
    }

    override fun onForceClose(context: SessionForceCloseContext) {
        if (!enabled) return
        calls.add(Call("listener", context, TransactionSynchronizationManager.isActualTransactionActive()))
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestSessionHooksConfig {
    @Bean
    fun switchableForceCloseHooks(): SwitchableForceCloseHooks = SwitchableForceCloseHooks()
}
