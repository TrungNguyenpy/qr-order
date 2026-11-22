package com.qrorder.support

import com.fasterxml.jackson.databind.JsonNode
import com.qrorder.table.qr.QrTokenCodec
import com.qrorder.table.session.InMemoryJoinRateLimiter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Nền cho integration test Giai đoạn 3 (bàn, QR, phiên): MockMvc + PostgreSQL thật (COMMIT thật), bộ ghi STOMP mọi
 * topic, rate limiter được xóa trước mỗi test, hook force-close thử nghiệm được tắt sau mỗi test.
 * Seed V2 (bàn T01..T10, T99 ngừng dùng, QR dev `dev-qr-T01`..`dev-qr-T10`) được DbCleaner trả về trước mỗi test.
 */
abstract class TableIntegrationTestBase : MenuIntegrationTestBase() {

    @Autowired protected lateinit var rateLimiter: InMemoryJoinRateLimiter
    @Autowired protected lateinit var qrCodec: QrTokenCodec
    @Autowired protected lateinit var hooks: SwitchableForceCloseHooks

    protected lateinit var stomp: StompTopicRecorder

    /** Mỗi lần join dùng một IP khác (trừ khi test chỉ định) để không chạm rate limit ngoài ý muốn. */
    private val ipSequence = AtomicInteger()

    @BeforeEach
    fun setUpTable() {
        rateLimiter.reset()
        hooks.reset()
        stomp = StompTopicRecorder(brokerChannel, objectMapper).start()
    }

    @AfterEach
    fun tearDownTable() {
        stomp.stop()
        hooks.reset()
    }

    protected fun newIp(): String = ipSequence.incrementAndGet().let { "10.20.${it / 250}.${it % 250 + 1}" }

    protected fun newDevice(): String = "dev-" + UUID.randomUUID().toString().replace("-", "").take(20)

    /** POST /api/v1/public/sessions/join từ địa chỉ [ip] (remoteAddr của request). */
    protected fun join(qrToken: String, deviceId: String = newDevice(), ip: String = newIp(), pin: String? = null): ResultActions {
        val body = mutableMapOf<String, Any?>("qrToken" to qrToken, "deviceId" to deviceId)
        if (pin != null) body["pin"] = pin
        val builder = MockMvcRequestBuilders.post("/api/v1/public/sessions/join").with { it.remoteAddr = ip; it }
        return api.perform(builder, null, body)
    }

    /** Join thành công, trả `data` (accessToken, newSession, session...). */
    protected fun joinOk(qrToken: String, deviceId: String = newDevice(), ip: String = newIp(), pin: String? = null): JsonNode =
        data(join(qrToken, deviceId, ip, pin).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk))

    protected fun tableId(code: String): Long = fx.tableId(code)

    protected fun tableStatus(id: Long): String = jdbc.queryForObject("SELECT status FROM dining_table WHERE id = ?", String::class.java, id)!!

    protected fun sessionStatus(id: Long): String = jdbc.queryForObject("SELECT status FROM table_session WHERE id = ?", String::class.java, id)!!

    /** Token trong URL QR (`.../t/{token}`). */
    protected fun tokenOf(url: String): String = url.substringAfterLast('/')

    /** Thêm Order OPEN + một món CONFIRMED (đơn chưa thanh toán - BR-S07) cho phiên. Trả về (orderId, itemId). */
    protected fun addUnpaidOrder(sessionId: Long): Pair<Long, Long> {
        val orderId = jdbc.queryForObject("INSERT INTO orders (session_id) VALUES (?) RETURNING id", Long::class.java, sessionId)!!
        val batchId = fx.addBatch(sessionId, orderId, 1)
        return orderId to fx.addConfirmedItem(orderId, batchId, 65_000)
    }

    /** Hủy món (CANCELLED, actor SYSTEM) bằng SQL - mô phỏng module Order (GĐ4). */
    protected fun cancelItem(itemId: Long) {
        jdbc.update(
            """UPDATE order_item SET status = 'CANCELLED', status_changed_by_type = 'SYSTEM', reason_code = 'CUSTOMER_REQUEST',
                      closed_at = now() WHERE id = ?""",
            itemId,
        )
    }

    /** Lùi last_activity_at của phiên [minutes] phút (theo đồng hồ DB) để mô phỏng idle. */
    protected fun makeIdle(sessionId: Long, minutes: Int) {
        jdbc.update("UPDATE table_session SET last_activity_at = now() - (? * interval '1 minute') WHERE id = ?", minutes, sessionId)
    }
}
