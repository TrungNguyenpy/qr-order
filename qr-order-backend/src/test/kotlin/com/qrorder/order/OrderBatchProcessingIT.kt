package com.qrorder.order

import com.qrorder.auth.domain.UserRole
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.OrderIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Staff xử lý đợt chờ xác nhận (FR-ORD-06): claim BR-O10, xác nhận/từ chối BR-O08, xác minh phiên BR-S04, real-time. */
class OrderBatchProcessingIT : OrderIntegrationTestBase() {

    private val staff2 by lazy { api.loggedIn("staff_two", UserRole.STAFF) }

    @Test
    fun `pending queue lists batches oldest first with items and table code`() {
        val a = guestAt("T01")
        val b = guestAt("T02")
        val (batchA, _) = placePho(a)
        val (batchB, _) = placePho(b)
        val queue = data(api.get("/api/v1/orders/batches/pending", staff.accessToken).andExpect(status().isOk))
        assertEquals(listOf(batchA, batchB), queue.map { it["id"].asLong() })
        assertEquals(listOf("T01", "T02"), queue.map { it["tableCode"].asText() })
        assertEquals("Phở bò tái", queue[0]["items"][0]["productName"].asText())
        assertEquals(false, queue[0]["claimActive"].asBoolean())
    }

    @Test
    fun `claim - exactly one of several concurrent staff wins, the loser gets 409 with the holder's name (BR-O10)`() {
        val guest = guestAt("T01")
        val (batchId, _) = placePho(guest)
        val tokens = listOf(staff.accessToken, staff2.accessToken)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val statuses = try {
            val futures = tokens.map { t ->
                pool.submit(Callable { start.await(); api.post("/api/v1/orders/batches/$batchId/claim", t, null).andReturn().response.status })
            }
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(listOf(200, 409), statuses.sorted(), statuses.toString())
        val claimedBy = jdbc.queryForObject("SELECT claimed_by FROM order_batch WHERE id = ?", Long::class.java, batchId)!!
        assertTrue(claimedBy == staff.id || claimedBy == staff2.id)
        assertNotNull(jdbc.queryForObject("SELECT claimed_at FROM order_batch WHERE id = ?", java.time.OffsetDateTime::class.java, batchId))
        assertEquals("PENDING", batchStatus(batchId), "Database không có trạng thái CLAIMED: claim là claimed_by/claimed_at")

        val loserToken = if (claimedBy == staff.id) staff2.accessToken else staff.accessToken
        val holderName = if (claimedBy == staff.id) staff.username else staff2.username
        val response = api.post("/api/v1/orders/batches/$batchId/claim", loserToken, null).isError(409, "BATCH_CLAIMED_BY_OTHER")
        assertTrue(api.json(response)["error"]["message"].asText().contains("Nhân viên $holderName"))
    }

    @Test
    fun `claiming again by the holder is allowed, an expired claim can be taken, Manager can take over, release frees the claim`() {
        val guest = guestAt("T01")
        val (batchId, _) = placePho(guest)
        api.post("/api/v1/orders/batches/$batchId/claim", staff.accessToken, null).andExpect(status().isOk)
        api.post("/api/v1/orders/batches/$batchId/claim", staff.accessToken, null).andExpect(status().isOk)
        api.post("/api/v1/orders/batches/$batchId/claim", staff2.accessToken, null).isError(409, "BATCH_CLAIMED_BY_OTHER")
        val queued = data(api.get("/api/v1/orders/batches/pending", staff2.accessToken))[0]
        assertEquals(true, queued["claimActive"].asBoolean())
        assertEquals(staff.id, queued["claimedBy"].asLong())

        // Hết hạn TTL (60s): người khác nhận được.
        jdbc.update("UPDATE order_batch SET claimed_at = now() - interval '61 seconds' WHERE id = ?", batchId)
        assertEquals(false, data(api.get("/api/v1/orders/batches/pending", staff2.accessToken))[0]["claimActive"].asBoolean())
        api.post("/api/v1/orders/batches/$batchId/claim", staff2.accessToken, null).andExpect(status().isOk)
        assertEquals(staff2.id, jdbc.queryForObject("SELECT claimed_by FROM order_batch WHERE id = ?", Long::class.java, batchId))

        // Manager giành quyền khi claim còn hạn.
        api.post("/api/v1/orders/batches/$batchId/claim", manager.accessToken, null).andExpect(status().isOk)
        assertEquals(manager.id, jdbc.queryForObject("SELECT claimed_by FROM order_batch WHERE id = ?", Long::class.java, batchId))

        // Chỉ người đang giữ mới nhả được.
        api.post("/api/v1/orders/batches/$batchId/release", staff.accessToken, null).isError(409, "BATCH_CLAIMED_BY_OTHER")
        api.post("/api/v1/orders/batches/$batchId/release", manager.accessToken, null).andExpect(status().isOk)
        assertEquals(null, jdbc.queryForObject("SELECT claimed_by FROM order_batch WHERE id = ?", Long::class.java, batchId))
    }

    @Test
    fun `confirm - items CONFIRMED, batch ACCEPTED, session verified, kitchen and guest notified, history records the staff`() {
        val guest = guestAt("T01")
        val (batchId, itemId) = placePho(guest)
        api.post("/api/v1/orders/batches/$batchId/claim", staff.accessToken, null).andExpect(status().isOk)
        val result = data(api.post("/api/v1/orders/batches/$batchId/confirm", staff.accessToken, null).andExpect(status().isOk))
        assertEquals("ACCEPTED", result["status"].asText())
        assertEquals("CONFIRMED", result["items"][0]["status"].asText())
        assertEquals(staff.id, result["decidedBy"].asLong())
        assertNotNull(result["decidedAt"].textValue())
        assertEquals("CONFIRMED", itemStatus(itemId))
        assertNotNull(jdbc.queryForObject("SELECT verified_at FROM table_session WHERE id = ?", java.time.OffsetDateTime::class.java, guest.sessionId))

        val h = history(itemId)
        assertEquals(listOf("PENDING", "CONFIRMED"), h.map { it["to_status"] })
        assertEquals("STAFF", h[1]["changed_by_type"])
        assertEquals(staff.id, h[1]["changed_by_user_id"])

        assertEquals(1, stomp.on("/topic/kitchen/orders").size)
        val toGuest = stomp.on("/topic/session/${guest.sessionId}").filter { it["change"]?.asText() == "BATCH_DECIDED" }
        assertEquals(1, toGuest.size)
        assertEquals("CONFIRMED", toGuest.single()["items"][0]["status"].asText())
        // Đợt đã xử lý: không còn trong hàng đợi và không xử lý lại được.
        assertEquals(0, data(api.get("/api/v1/orders/batches/pending", staff.accessToken)).size())
        api.post("/api/v1/orders/batches/$batchId/confirm", staff.accessToken, null).isError(409, "BATCH_NOT_PENDING")
        api.post("/api/v1/orders/batches/$batchId/claim", staff.accessToken, null).isError(409, "BATCH_NOT_PENDING")
    }

    @Test
    fun `partial confirm - rejected items need a reason, batch PARTIALLY_ACCEPTED, only accepted items reach the kitchen`() {
        val guest = guestAt("T01")
        val order = placeOk(guest, phoSmall(), line("Trà đá"), line("Chả giò"))
        val batch = order["batches"][0]
        val batchId = batch["id"].asLong()
        val ids = batch["items"].map { it["id"].asLong() }
        api.post("/api/v1/orders/batches/$batchId/confirm", staff.accessToken, mapOf("rejectItems" to listOf(mapOf("itemId" to ids[2]))))
            .isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/orders/batches/$batchId/confirm", staff.accessToken, mapOf("rejectItems" to listOf(mapOf("itemId" to ids[2], "reasonCode" to "hết hàng"))))
            .isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/orders/batches/$batchId/confirm", staff.accessToken, mapOf("rejectItems" to listOf(mapOf("itemId" to 424242, "reasonCode" to "OUT_OF_STOCK"))))
            .isError(400, "VALIDATION_FAILED")
        assertEquals("PENDING", batchStatus(batchId), "Yêu cầu sai không đổi gì")

        val result = data(
            api.post(
                "/api/v1/orders/batches/$batchId/confirm", staff.accessToken,
                mapOf("rejectItems" to listOf(mapOf("itemId" to ids[2], "reasonCode" to "OUT_OF_STOCK", "reasonNote" to "Hết chả giò"))),
            ).andExpect(status().isOk),
        )
        assertEquals("PARTIALLY_ACCEPTED", result["status"].asText())
        assertEquals(listOf("CONFIRMED", "CONFIRMED", "REJECTED"), result["items"].map { it["status"].asText() })
        assertEquals("OUT_OF_STOCK", result["items"][2]["reasonCode"].asText())
        assertEquals("OUT_OF_STOCK", history(ids[2]).last()["reason_code"])
        // Thông báo bếp gồm cả món bị từ chối (kèm trạng thái REJECTED) để màn hình bếp/khách cập nhật đồng bộ.
        val kitchenItems = stomp.on("/topic/kitchen/orders").single()["items"]
        assertEquals(listOf("CONFIRMED", "CONFIRMED", "REJECTED"), kitchenItems.map { it["status"].asText() })
    }

    @Test
    fun `rejecting items one by one - every item rejected makes the batch REJECTED and the session stays unverified`() {
        val guest = guestAt("T01")
        val batch = placeOk(guest, phoSmall(), line("Trà đá"))["batches"][0]
        val batchId = batch["id"].asLong()
        val rejects = batch["items"].map { mapOf("itemId" to it["id"].asLong(), "reasonCode" to "OUT_OF_STOCK") }
        val result = data(api.post("/api/v1/orders/batches/$batchId/confirm", staff.accessToken, mapOf("rejectItems" to rejects)).andExpect(status().isOk))
        assertEquals("REJECTED", result["status"].asText())
        assertEquals("OUT_OF_STOCK", result["reasonCode"].asText())
        assertEquals(null, jdbc.queryForObject("SELECT verified_at FROM table_session WHERE id = ?", java.time.OffsetDateTime::class.java, guest.sessionId))
        assertEquals(0, stomp.on("/topic/kitchen/orders").size)
    }

    @Test
    fun `reject whole batch - reason is mandatory, all items REJECTED, guest sees the reason`() {
        val guest = guestAt("T01")
        val (batchId, itemId) = placePho(guest)
        api.post("/api/v1/orders/batches/$batchId/reject", staff.accessToken, mapOf("reasonNote" to "x")).isError(400, "VALIDATION_FAILED")
        val result = data(
            api.post("/api/v1/orders/batches/$batchId/reject", staff.accessToken, mapOf("reasonCode" to "SUSPECTED_FAKE_ORDER", "reasonNote" to "Không có khách tại bàn"))
                .andExpect(status().isOk),
        )
        assertEquals("REJECTED", result["status"].asText())
        assertEquals("SUSPECTED_FAKE_ORDER", result["reasonCode"].asText())
        assertEquals("REJECTED", itemStatus(itemId))
        assertNotNull(jdbc.queryForObject("SELECT closed_at FROM order_item WHERE id = ?", java.time.OffsetDateTime::class.java, itemId))
        val view = data(api.get("/api/v1/guest/orders", guest.token))
        assertEquals("REJECTED", view["batches"][0]["status"].asText())
        assertEquals("SUSPECTED_FAKE_ORDER", view["batches"][0]["items"][0]["reasonCode"].asText())
        assertEquals(0L, view["billableTotal"].asLong())
    }

    @Test
    fun `a valid claim of another staff blocks confirm and reject (BR-O10), Manager may decide, an expired claim does not block`() {
        val guest = guestAt("T01")
        val (batchId, _) = placePho(guest)
        api.post("/api/v1/orders/batches/$batchId/claim", staff.accessToken, null).andExpect(status().isOk)
        api.post("/api/v1/orders/batches/$batchId/confirm", staff2.accessToken, null).isError(409, "BATCH_CLAIMED_BY_OTHER")
        api.post("/api/v1/orders/batches/$batchId/reject", staff2.accessToken, mapOf("reasonCode" to "OTHER_REASON")).isError(409, "BATCH_CLAIMED_BY_OTHER")
        assertEquals("PENDING", batchStatus(batchId))

        jdbc.update("UPDATE order_batch SET claimed_at = now() - interval '2 minutes' WHERE id = ?", batchId)
        api.post("/api/v1/orders/batches/$batchId/confirm", staff2.accessToken, null).andExpect(status().isOk)
        assertEquals("ACCEPTED", batchStatus(batchId))

        val other = guestAt("T02")
        val (batch2, _) = placePho(other)
        api.post("/api/v1/orders/batches/$batch2/claim", staff.accessToken, null).andExpect(status().isOk)
        api.post("/api/v1/orders/batches/$batch2/confirm", manager.accessToken, null).andExpect(status().isOk)
    }

    @Test
    fun `concurrent confirm and guest cancel of the same item never leave an inconsistent batch`() {
        val guest = guestAt("T01")
        val (batchId, itemId) = placePho(guest)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val confirm = pool.submit(Callable { start.await(); api.post("/api/v1/orders/batches/$batchId/confirm", staff.accessToken, null).andReturn().response.status })
            val cancel = pool.submit(Callable { start.await(); api.post("/api/v1/guest/orders/items/$itemId/cancel", guest.token, null).andReturn().response.status })
            start.countDown()
            val results = listOf(confirm.get(30, TimeUnit.SECONDS), cancel.get(30, TimeUnit.SECONDS))
            // Khóa bàn/phiên tuần tự hóa hai thao tác: kết quả hợp lệ là (confirm xong rồi hủy CONFIRMED) hoặc (hủy rồi confirm 409).
            assertTrue(results[1] == 200, results.toString())
            assertTrue(results[0] == 200 || results[0] == 409, results.toString())
        } finally {
            pool.shutdownNow()
        }
        assertEquals("CANCELLED", itemStatus(itemId))
        assertTrue(batchStatus(batchId) in setOf("CANCELLED"), "Đợt đồng bộ theo món: ${batchStatus(batchId)}")
    }

    @Test
    fun `only floor staff can process batches, guest and kitchen and ADMIN cannot`() {
        val guest = guestAt("T01")
        val (batchId, _) = placePho(guest)
        val admin = api.loggedIn("admin_orders", UserRole.ADMIN)
        listOf(guest.token, kitchen.accessToken, admin.accessToken).forEach { t ->
            api.get("/api/v1/orders/batches/pending", t).isError(403, "PERMISSION_DENIED")
            api.post("/api/v1/orders/batches/$batchId/claim", t, null).isError(403, "PERMISSION_DENIED")
            api.post("/api/v1/orders/batches/$batchId/confirm", t, null).isError(403, "PERMISSION_DENIED")
        }
        api.get("/api/v1/orders/batches/pending", null).isError(401, "UNAUTHENTICATED")
        api.post("/api/v1/orders/batches/999999/claim", staff.accessToken, null).isError(404, "RESOURCE_NOT_FOUND")
    }

    @Test
    fun `an order of a force-closed or locked session can no longer be confirmed`() {
        val guest = guestAt("T01")
        val (batchId, _) = placePho(guest)
        jdbc.update("UPDATE orders SET status = 'LOCKED' WHERE session_id = ?", guest.sessionId)
        api.post("/api/v1/orders/batches/$batchId/confirm", staff.accessToken, null).isError(409, "ORDER_NOT_OPEN")
        assertEquals("PENDING", batchStatus(batchId))
    }

    @Test
    fun `staff order view shows batches of a session`() {
        val guest = guestAt("T01")
        placePho(guest)
        val view = data(api.get("/api/v1/orders/sessions/${guest.sessionId}", staff.accessToken).andExpect(status().isOk))
        assertEquals("T01", view["tableCode"].asText())
        assertEquals("OPEN", view["status"].asText())
        assertEquals(1, view["batches"].size())
        val empty = data(api.get("/api/v1/orders/sessions/${guestAt("T02").sessionId}", staff.accessToken).andExpect(status().isOk))
        assertEquals(0, empty["batches"].size())
        api.get("/api/v1/orders/sessions/999999", staff.accessToken).isError(404, "RESOURCE_NOT_FOUND")
    }
}
