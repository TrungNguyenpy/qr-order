package com.qrorder.support

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.BeforeEach
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Khách đã vào phiên: Guest JWT, phiên và thiết bị. */
data class GuestCtx(val token: String, val sessionId: Long, val deviceId: String, val tableCode: String)

/**
 * Nền cho integration test Giai đoạn 4 (order, kitchen): MockMvc + PostgreSQL thật (COMMIT thật), bộ ghi STOMP mọi topic.
 * Khách vào phiên qua API thật (join QR dev), món lấy từ seed V2.
 */
abstract class OrderIntegrationTestBase : TableIntegrationTestBase() {

    /** Rate limit đặt món được nới cho mọi test (mỗi lần gọi, kể cả lỗi, đều được đếm); test rate limit tự đặt lại về 5. */
    @BeforeEach
    fun relaxOrderRateLimit() {
        jdbc.update("UPDATE restaurant_settings SET order_rate_limit_per_minute = 1000")
    }

    protected val pho = "Phở bò tái"
    protected val coffee = "Cà phê sữa đá"

    /** Khách quét QR dev của bàn [tableCode] bằng thiết bị mới. */
    protected fun guestAt(tableCode: String = "T01", deviceId: String = newDevice()): GuestCtx {
        val data = joinOk("dev-qr-$tableCode", deviceId)
        return GuestCtx(data["accessToken"].asText(), data["session"]["sessionId"].asLong(), deviceId, tableCode)
    }

    /** Thiết bị thứ hai vào cùng phiên (BR-S01). */
    protected fun anotherDevice(of: GuestCtx): GuestCtx = guestAt(of.tableCode)

    protected fun setMode(mode: String) {
        jdbc.update("UPDATE restaurant_settings SET order_confirmation_mode = ?", mode)
    }

    /** Một dòng món trong request; option theo (tên nhóm, tên lựa chọn). */
    protected fun line(product: String, quantity: Int = 1, options: List<Pair<String, String>> = emptyList(), note: String? = null): Map<String, Any?> =
        mapOf(
            "productId" to productId(product),
            "quantity" to quantity,
            "note" to note,
            "optionItemIds" to options.map { (g, i) -> optionItemId(g, i) },
        )

    /** Phở cỡ "Nhỏ" (nhóm Kích cỡ bắt buộc, phụ thu 0). */
    protected fun phoSmall(quantity: Int = 1) = line(pho, quantity, listOf("Kích cỡ" to "Nhỏ"))

    protected fun place(guest: GuestCtx, vararg lines: Map<String, Any?>, clientRequestId: UUID = UUID.randomUUID()): ResultActions =
        api.post("/api/v1/guest/orders", guest.token, mapOf("clientRequestId" to clientRequestId.toString(), "items" to lines.toList()))

    protected fun placeOk(guest: GuestCtx, vararg lines: Map<String, Any?>, clientRequestId: UUID = UUID.randomUUID()): JsonNode =
        data(place(guest, *lines, clientRequestId = clientRequestId).andExpect(status().isCreated))

    /** Đặt một đợt một món Phở và trả (batchId, itemId). */
    protected fun placePho(guest: GuestCtx): Pair<Long, Long> {
        val order = placeOk(guest, phoSmall())
        val batch = order["batches"].last()
        return batch["id"].asLong() to batch["items"][0]["id"].asLong()
    }

    protected fun batchStatus(id: Long): String = jdbc.queryForObject("SELECT status FROM order_batch WHERE id = ?", String::class.java, id)!!

    protected fun itemStatus(id: Long): String = jdbc.queryForObject("SELECT status FROM order_item WHERE id = ?", String::class.java, id)!!

    protected fun orderStatusOf(sessionId: Long): String =
        jdbc.queryForObject("SELECT status FROM orders WHERE session_id = ?", String::class.java, sessionId)!!

    /**
     * Món đã CONFIRMED. Đợt PENDING thì Staff xác nhận; đợt đã tự CONFIRMED (FIRST_BATCH_ONLY sau khi phiên được xác minh,
     * hoặc chế độ NONE) thì không gọi confirm (API trả 409 BATCH_NOT_PENDING là đúng nghiệp vụ).
     */
    protected fun placeAndConfirm(guest: GuestCtx, staffToken: String): Pair<Long, Long> {
        val (batchId, itemId) = placePho(guest)
        if (batchStatus(batchId) == "PENDING") {
            api.post("/api/v1/orders/batches/$batchId/confirm", staffToken, null).andExpect(status().isOk)
        }
        check(itemStatus(itemId) == "CONFIRMED") { "Món $itemId phải CONFIRMED, đang ${itemStatus(itemId)}" }
        return batchId to itemId
    }

    protected fun history(itemId: Long): List<Map<String, Any?>> = jdbc.queryForList(
        "SELECT from_status, to_status, changed_by_type, changed_by_user_id, device_id, reason_code FROM order_item_status_history WHERE order_item_id = ? ORDER BY id",
        itemId,
    )
}
