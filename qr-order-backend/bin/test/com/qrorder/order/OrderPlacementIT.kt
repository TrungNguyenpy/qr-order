package com.qrorder.order

import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.OrderIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Khách đặt món (FR-ORD-01..04): chế độ duyệt đợt, snapshot BR-O01, option BR-O02/O03 + BE-18, idempotency BR-O04,
 * rate limit BR-S09, PIN BE-23, trạng thái phiên/Order, và thông báo real-time sau COMMIT.
 */
class OrderPlacementIT : OrderIntegrationTestBase() {

    @Test
    fun `first batch is PENDING, snapshots name price and options, and creates exactly one order (FIRST_BATCH_ONLY)`() {
        val guest = guestAt("T01")
        val order = placeOk(guest, line(pho, 2, listOf("Kích cỡ" to "Vừa", "Thêm món" to "Thêm trứng"), note = "  ít hành  "))

        assertEquals("OPEN", order["status"].asText())
        assertEquals(false, order["replayed"].asBoolean())
        val batch = order["batches"].single()
        assertEquals(1, batch["seq"].asInt())
        assertEquals("PENDING", batch["status"].asText())
        assertEquals(true, batch["requiresConfirmation"].asBoolean())
        val item = batch["items"].single()
        assertEquals("PENDING", item["status"].asText())
        assertEquals("Phở bò tái", item["productName"].asText())
        assertEquals(65_000L, item["unitPrice"].asLong())
        assertEquals(13_000L, item["optionExtraPerUnit"].asLong(), "Vừa 5.000 + Thêm trứng 8.000")
        assertEquals(156_000L, item["lineTotal"].asLong(), "(65.000 + 13.000) x 2 - BE-04")
        assertEquals("ít hành", item["note"].asText())
        assertEquals(setOf("Vừa", "Thêm trứng"), item["options"].map { it["optionName"].asText() }.toSet())
        assertEquals(0L, order["billableTotal"].asLong(), "PENDING chưa phải món tính tiền (BR-P18)")

        assertEquals(1L, count("SELECT count(*) FROM orders WHERE session_id = ?", guest.sessionId))
        assertEquals(2L, count("SELECT count(*) FROM order_item_option WHERE order_item_id = ?", item["id"].asLong()))
        assertEquals(listOf("PENDING"), history(item["id"].asLong()).map { it["to_status"] })
        assertEquals(guest.deviceId, history(item["id"].asLong()).single()["device_id"])

        // Đợt thứ hai (đợt đầu chưa được xác nhận) vẫn thuộc cùng Order (DEC-02 A) và vẫn cần xác nhận.
        val second = placeOk(guest, line("Trà đá"))
        assertEquals(1L, count("SELECT count(*) FROM orders WHERE session_id = ?", guest.sessionId))
        assertEquals(listOf(1, 2), second["batches"].map { it["seq"].asInt() })
        assertEquals("PENDING", second["batches"][1]["status"].asText(), "Phiên chưa xác minh: đợt 2 vẫn PENDING")
    }

    @Test
    fun `price changes after ordering never alter the snapshot (BR-O01)`() {
        val guest = guestAt("T01")
        val order = placeOk(guest, phoSmall())
        jdbc.update("UPDATE product SET price = 99000, name = 'Phở đổi tên' WHERE name = ?", pho)
        jdbc.update("UPDATE option_item SET extra_price = 50000 WHERE name = 'Nhỏ'")
        val item = data(api.get("/api/v1/guest/orders", guest.token).andExpect(status().isOk))["batches"].single()["items"].single()
        assertEquals(order["batches"][0]["items"][0]["id"].asLong(), item["id"].asLong())
        assertEquals("Phở bò tái", item["productName"].asText())
        assertEquals(65_000L, item["unitPrice"].asLong())
        assertEquals(65_000L, item["lineTotal"].asLong())
    }

    @Test
    fun `FIRST_BATCH_ONLY - once staff confirmed the first batch the session is verified and later batches auto-CONFIRM`() {
        val guest = guestAt("T01")
        val (batchId, _) = placePho(guest)
        assertNull(jdbc.queryForObject("SELECT verified_at FROM table_session WHERE id = ?", java.time.OffsetDateTime::class.java, guest.sessionId))
        api.post("/api/v1/orders/batches/$batchId/confirm", staff.accessToken, null).andExpect(status().isOk)
        assertNotNull(jdbc.queryForObject("SELECT verified_at FROM table_session WHERE id = ?", java.time.OffsetDateTime::class.java, guest.sessionId))

        val order = placeOk(guest, line("Trà đá", 2))
        val second = order["batches"][1]
        assertEquals(2, second["seq"].asInt())
        assertEquals(false, second["requiresConfirmation"].asBoolean())
        assertEquals("ACCEPTED", second["status"].asText())
        assertEquals("CONFIRMED", second["items"].single()["status"].asText())
        assertEquals(0L, second["items"].single()["lineTotal"].asLong(), "Trà đá giá 0")
        assertNotNull(second["items"].single()["confirmedAt"].textValue())
        assertEquals(65_000L, order["billableTotal"].asLong())
    }

    @Test
    fun `EVERY_BATCH mode (ALL) keeps every batch PENDING even after the first confirmation`() {
        setMode("ALL")
        val guest = guestAt("T01")
        val (batchId, _) = placePho(guest)
        api.post("/api/v1/orders/batches/$batchId/confirm", staff.accessToken, null).andExpect(status().isOk)
        val second = placeOk(guest, line("Trà đá"))["batches"][1]
        assertEquals("PENDING", second["status"].asText())
        assertEquals(true, second["requiresConfirmation"].asBoolean())
    }

    @Test
    fun `AUTO_CONFIRM mode (NONE) confirms every batch immediately, kitchen is notified`() {
        setMode("NONE")
        val guest = guestAt("T01")
        val order = placeOk(guest, phoSmall())
        val batch = order["batches"].single()
        assertEquals("ACCEPTED", batch["status"].asText())
        assertEquals("CONFIRMED", batch["items"].single()["status"].asText())
        assertEquals(65_000L, order["billableTotal"].asLong())
        assertEquals(1, stomp.on("/topic/kitchen/orders").size, "Món CONFIRMED đi thẳng vào bếp")
        assertEquals(listOf("CONFIRMED"), history(batch["items"][0]["id"].asLong()).map { it["to_status"] })
    }

    @Test
    fun `replaying the same clientRequestId returns the first result and creates nothing new (BR-O04)`() {
        val guest = guestAt("T01")
        val requestId = UUID.randomUUID()
        val first = placeOk(guest, phoSmall(), clientRequestId = requestId)
        val replay = data(place(guest, phoSmall(2), line("Trà đá"), clientRequestId = requestId).andExpect(status().isOk))
        assertEquals(true, replay["replayed"].asBoolean())
        assertEquals(first["batches"][0]["id"].asLong(), replay["batchId"].asLong())
        assertEquals(1, replay["batches"].size())
        assertEquals(1, replay["batches"][0]["items"].size())
        assertEquals(1L, count("SELECT count(*) FROM order_batch WHERE session_id = ?", guest.sessionId))
        assertEquals(1, stomp.on("/topic/staff/orders").size, "Gửi lại không phát thêm thông báo")
    }

    @Test
    fun `concurrent submissions with the same clientRequestId create exactly one batch`() {
        val guest = guestAt("T01")
        val requestId = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val results = (1..4).map {
                pool.submit(Callable {
                    start.await()
                    place(guest, phoSmall(), clientRequestId = requestId).andReturn().response.status
                })
            }
            start.countDown()
            val statuses = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, statuses.count { it == 201 }, statuses.toString())
            assertEquals(3, statuses.count { it == 200 }, statuses.toString())
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1L, count("SELECT count(*) FROM order_batch WHERE session_id = ?", guest.sessionId))
        assertEquals(1L, count("SELECT count(*) FROM order_item WHERE order_id IN (SELECT id FROM orders WHERE session_id = ?)", guest.sessionId))
    }

    @Test
    fun `concurrent different batches get distinct sequence numbers in one order`() {
        setMode("NONE")
        val guest = guestAt("T01")
        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        try {
            val results = (1..3).map {
                pool.submit(Callable { start.await(); place(guest, line("Trà đá")).andReturn().response.status })
            }
            start.countDown()
            assertEquals(listOf(201, 201, 201), results.map { it.get(30, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1L, count("SELECT count(*) FROM orders WHERE session_id = ?", guest.sessionId))
        assertEquals(listOf(1, 2, 3), jdbc.queryForList("SELECT seq FROM order_batch WHERE session_id = ? ORDER BY seq", Int::class.java, guest.sessionId))
    }

    @Test
    fun `rate limit - more than order_rate_limit_per_minute submissions per session gets 429 with Retry-After (BR-S09)`() {
        setMode("NONE")
        jdbc.update("UPDATE restaurant_settings SET order_rate_limit_per_minute = 5")
        val guest = guestAt("T01")
        repeat(5) { placeOk(guest, line("Trà đá")) }
        place(guest, line("Trà đá")).isError(429, "RATE_LIMIT_EXCEEDED").andExpect(header().exists("Retry-After"))
        assertEquals(5L, count("SELECT count(*) FROM order_batch WHERE session_id = ?", guest.sessionId))
        // Giới hạn theo phiên: bàn khác không bị ảnh hưởng.
        placeOk(guestAt("T02"), line("Trà đá"))
    }

    @Test
    fun `session in BILLING or closed does not accept orders`() {
        val guest = guestAt("T01")
        jdbc.update("UPDATE table_session SET status = 'BILLING', billing_started_at = now() WHERE id = ?", guest.sessionId)
        place(guest, phoSmall()).isError(409, "SESSION_NOT_OPEN")
        assertEquals(0L, count("SELECT count(*) FROM order_batch WHERE session_id = ?", guest.sessionId))

        val other = guestAt("T02")
        forceCloseSession(other.sessionId)
        place(other, phoSmall()).isError(401, "SESSION_CLOSED")
    }

    @Test
    fun `LOCKED order does not accept new batches (BR-O12)`() {
        val guest = guestAt("T01")
        placePho(guest)
        jdbc.update("UPDATE orders SET status = 'LOCKED' WHERE session_id = ?", guest.sessionId)
        place(guest, line("Trà đá")).isError(409, "ORDER_NOT_OPEN")
    }

    @Test
    fun `table PIN - required until this device verified it (BE-23)`() {
        jdbc.update("UPDATE restaurant_settings SET require_table_pin = true")
        val guest = guestAt("T01")
        place(guest, phoSmall()).isError(403, "TABLE_PIN_REQUIRED")
        val pin = jdbc.queryForObject("SELECT table_pin FROM table_session WHERE id = ?", String::class.java, guest.sessionId)!!.trim()
        api.post("/api/v1/guest/session/verify-pin", guest.token, mapOf("pin" to pin)).andExpect(status().isOk)
        placeOk(guest, phoSmall())

        // Thiết bị thứ hai chưa nhập PIN vẫn bị chặn.
        val second = anotherDevice(guest)
        place(second, phoSmall()).isError(403, "TABLE_PIN_REQUIRED")
    }

    @Test
    fun `table PIN is not required when the setting is off or the session has no PIN`() {
        val guest = guestAt("T01")
        placeOk(guest, phoSmall())
        jdbc.update("UPDATE restaurant_settings SET require_table_pin = true")
        jdbc.update("UPDATE table_session SET table_pin = NULL WHERE id = ?", guest.sessionId)
        placeOk(guest, line("Trà đá"))
    }

    @Test
    fun `SOLD_OUT, HIDDEN products and hidden categories cannot be ordered (BR-O02)`() {
        val guest = guestAt("T01")
        place(guest, line("Mì xào hải sản")).isError(409, "PRODUCT_NOT_AVAILABLE")
        place(guest, line("Bánh flan")).isError(409, "PRODUCT_NOT_AVAILABLE")
        jdbc.update("UPDATE category SET is_visible = false WHERE name = 'Khai vị'")
        place(guest, line("Chả giò")).isError(409, "PRODUCT_NOT_AVAILABLE")
        place(guest, mapOf("productId" to 999_999, "quantity" to 1)).isError(422, "INVALID_REFERENCE")
        // Một dòng hỏng thì cả đợt không được tạo.
        place(guest, line("Trà đá"), line("Mì xào hải sản")).isError(409, "PRODUCT_NOT_AVAILABLE")
        assertEquals(0L, count("SELECT count(*) FROM order_batch WHERE session_id = ?", guest.sessionId))
        assertEquals(0L, count("SELECT count(*) FROM orders WHERE session_id = ?", guest.sessionId))
    }

    @Test
    fun `option rules - required group, max select, foreign or inactive option, duplicates (BR-O03)`() {
        val guest = guestAt("T01")
        place(guest, line(pho)).isError(422, "INVALID_OPTION_SELECTION") // thiếu Kích cỡ bắt buộc
        place(guest, line(pho, options = listOf("Kích cỡ" to "Nhỏ", "Kích cỡ" to "Lớn"))).isError(422, "INVALID_OPTION_SELECTION")
        place(guest, line(pho, options = listOf("Kích cỡ" to "Nhỏ", "Mức đá" to "Ít đá"))).isError(422, "INVALID_OPTION_SELECTION") // nhóm không gắn món
        place(guest, line("Trà đào cam sả", options = listOf("Kích cỡ" to "Nhỏ", "Mức đường" to "50%",
            "Topping" to "Trân châu", "Topping" to "Thạch đào", "Topping" to "Kem cheese"))).andExpect(status().isCreated) // đúng 3 topping
        val dup = optionItemId("Kích cỡ", "Nhỏ")
        place(guest, mapOf("productId" to productId(pho), "quantity" to 1, "optionItemIds" to listOf(dup, dup)))
            .isError(422, "INVALID_OPTION_SELECTION")
        jdbc.update("UPDATE option_item SET is_active = false WHERE name = 'Lớn'")
        place(guest, line(pho, options = listOf("Kích cỡ" to "Lớn"))).isError(422, "INVALID_OPTION_SELECTION")
    }

    @Test
    fun `a required group whose items are all inactive is hidden, so it is not required (BE-18)`() {
        val guest = guestAt("T01")
        jdbc.update("UPDATE option_item SET is_active = false WHERE option_group_id = (SELECT id FROM option_group WHERE name = 'Kích cỡ')")
        placeOk(guest, line(pho))
        jdbc.update("UPDATE option_group SET is_active = false WHERE name = 'Thêm món'")
        place(guest, line(pho, options = listOf("Thêm món" to "Thêm thịt"))).isError(422, "INVALID_OPTION_SELECTION")
    }

    @Test
    fun `quantity and payload validation`() {
        val guest = guestAt("T01")
        listOf(0, -1, 100).forEach { q -> place(guest, phoSmall(q)).isError(400, "VALIDATION_FAILED") }
        jdbc.update("UPDATE restaurant_settings SET max_item_quantity = 5")
        place(guest, phoSmall(6)).isError(400, "VALIDATION_FAILED")
        placeOk(guest, phoSmall(5))
        api.post("/api/v1/guest/orders", guest.token, mapOf("clientRequestId" to UUID.randomUUID().toString(), "items" to emptyList<Any>()))
            .isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/guest/orders", guest.token, mapOf("items" to listOf(phoSmall()))).isError(400, "VALIDATION_FAILED")
        api.postRaw("/api/v1/guest/orders", guest.token, """{"clientRequestId":"khong-phai-uuid","items":[]}""").isError(400, "MALFORMED_REQUEST")
    }

    @Test
    fun `pending batch cap per session (max_pending_batches_per_session)`() {
        val guest = guestAt("T01")
        repeat(3) { placeOk(guest, line("Trà đá")) }
        place(guest, line("Trà đá")).isError(409, "MAX_PENDING_BATCHES_REACHED")
        // Xác nhận một đợt thì gửi tiếp được.
        val firstBatch = jdbc.queryForObject("SELECT min(id) FROM order_batch WHERE session_id = ?", Long::class.java, guest.sessionId)!!
        api.post("/api/v1/orders/batches/$firstBatch/confirm", staff.accessToken, null).andExpect(status().isOk)
        placeOk(guest, line("Trà đá"))
    }

    @Test
    fun `placing an order refreshes the session activity time`() {
        val guest = guestAt("T01")
        makeIdle(guest.sessionId, 120)
        val before = jdbc.queryForObject("SELECT last_activity_at FROM table_session WHERE id = ?", java.time.OffsetDateTime::class.java, guest.sessionId)!!
        placeOk(guest, phoSmall())
        val after = jdbc.queryForObject("SELECT last_activity_at FROM table_session WHERE id = ?", java.time.OffsetDateTime::class.java, guest.sessionId)!!
        assertTrue(after.isAfter(before.plusMinutes(100)), "last_activity_at phải được làm mới")
    }

    @Test
    fun `devices of the same session share one order, and only guests with a valid token can order`() {
        val first = guestAt("T01")
        val second = anotherDevice(first)
        placeOk(first, phoSmall())
        placeOk(second, line("Trà đá"))
        assertEquals(1L, count("SELECT count(*) FROM orders WHERE session_id = ?", first.sessionId))
        val view = data(api.get("/api/v1/guest/orders", second.token).andExpect(status().isOk))
        assertEquals(2, view["batches"].size(), "Cả hai thiết bị thấy cùng một Order")
        assertEquals(setOf(first.deviceId, second.deviceId), jdbc.queryForList("SELECT device_id FROM order_batch WHERE session_id = ?", String::class.java, first.sessionId).toSet())

        api.post("/api/v1/guest/orders", null, mapOf("clientRequestId" to UUID.randomUUID().toString(), "items" to listOf(phoSmall()))).isError(401, "UNAUTHENTICATED")
        api.post("/api/v1/guest/orders", staff.accessToken, mapOf("clientRequestId" to UUID.randomUUID().toString(), "items" to listOf(phoSmall()))).isError(403, "PERMISSION_DENIED")
    }

    @Test
    fun `guest order view is empty before the first batch`() {
        val guest = guestAt("T03")
        api.get("/api/v1/guest/orders", guest.token).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.orderId").doesNotExist())
            .andExpect(jsonPath("$.data.batches.length()").value(0))
            .andExpect(jsonPath("$.data.billableTotal").value(0))
    }

    @Test
    fun `real-time - messages are sent only after commit, to session and staff topics, without staff identity`() {
        val guest = guestAt("T01")
        placeOk(guest, phoSmall())
        val toSession = stomp.on("/topic/session/${guest.sessionId}").filter { it["type"]?.asText() == "ORDER_CHANGED" }
        val toStaff = stomp.on("/topic/staff/orders")
        assertEquals(1, toSession.size)
        assertEquals(1, toStaff.size)
        assertEquals(0, stomp.on("/topic/kitchen/orders").size, "Đợt PENDING chưa vào bếp")
        val message = toSession.single()
        assertEquals("BATCH_SUBMITTED", message["change"].asText())
        assertEquals("T01", message["tableCode"].asText())
        assertEquals("Phở bò tái", message["items"][0]["productName"].asText())
        assertTrue(message["eventId"].asLong() > 0)
        assertTrue(message.fieldNames().asSequence().none { it.contains("user", true) || it.contains("staff", true) })

        // Đặt lỗi (rollback / bị chặn) không phát gì.
        val before = stomp.sent.size
        place(guest, line("Mì xào hải sản")).isError(409, "PRODUCT_NOT_AVAILABLE")
        assertEquals(before, stomp.sent.size)
    }

    private fun forceCloseSession(sessionId: Long) {
        api.post("/api/v1/sessions/$sessionId/force-close", manager.accessToken, mapOf("reason" to "Đóng thử")).andExpect(status().isOk)
    }

}
