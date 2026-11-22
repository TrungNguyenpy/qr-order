package com.qrorder.order

import com.fasterxml.jackson.databind.JsonNode
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.BillingIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GĐ6 - FR-ORD-05 (BE-37): nhân viên đặt món hộ khách, đổi số lượng món PENDING/CONFIRMED và hủy trực tiếp món
 * PENDING/CONFIRMED kèm lý do; món PREPARING/READY vẫn phải qua yêu cầu hủy và Manager duyệt (BR-O06).
 */
class StaffOrderIT : BillingIntegrationTestBase() {

    private fun staffPlace(
        sessionId: Long,
        vararg lines: Map<String, Any?>,
        token: String? = staff.accessToken,
        clientRequestId: UUID = UUID.randomUUID(),
    ): ResultActions = api.post(
        "/api/v1/orders/sessions/$sessionId/batches", token,
        mapOf("clientRequestId" to clientRequestId.toString(), "items" to lines.toList()),
    )

    private fun staffPlaceOk(sessionId: Long, vararg lines: Map<String, Any?>, token: String = staff.accessToken): JsonNode =
        data(staffPlace(sessionId, *lines, token = token).andExpect(status().isCreated))

    private fun changeQuantity(itemId: Long, quantity: Int?, version: Long?, token: String? = staff.accessToken): ResultActions =
        api.patch("/api/v1/orders/items/$itemId/quantity", token, mapOf("quantity" to quantity, "version" to version))

    private fun staffCancelItem(itemId: Long, reason: String? = "Khách đổi ý", token: String? = staff.accessToken): ResultActions =
        api.post("/api/v1/orders/items/$itemId/cancel", token, mapOf("reason" to reason))

    private fun itemVersion(itemId: Long): Long = version("order_item", itemId)

    // ================================================================================================ Đặt món hộ

    @Test
    fun `staff places a batch for a session without an order - batch ACCEPTED, items CONFIRMED, kitchen notified`() {
        val guest = guestAt("T01")
        assertEquals(0L, count("SELECT count(*) FROM orders WHERE session_id = ?", guest.sessionId))

        val result = staffPlaceOk(guest.sessionId, phoSmall(2), coffeeSmall())

        assertEquals(false, result["replayed"].asBoolean())
        assertEquals(159_000L, result["billableTotal"].asLong())
        val batch = result["batch"]
        assertEquals("ACCEPTED", batch["status"].asText())
        assertEquals(false, batch["requiresConfirmation"].asBoolean())
        assertEquals(1, batch["seq"].asInt())
        assertEquals(staff.id, batch["decidedBy"].asLong())
        assertEquals(listOf("CONFIRMED", "CONFIRMED"), batch["items"].map { it["status"].asText() })
        assertEquals(listOf(130_000L, 29_000L), batch["items"].map { it["lineTotal"].asLong() })

        val row = jdbc.queryForMap("SELECT submitted_by_type, submitted_by_user_id, device_id, requires_confirmation, decided_at FROM order_batch")
        assertEquals("STAFF", row["submitted_by_type"])
        assertEquals(staff.id, (row["submitted_by_user_id"] as Number).toLong())
        assertNull(row["device_id"])
        assertEquals(false, row["requires_confirmation"])
        assertTrue(row["decided_at"] != null)
        assertEquals("OPEN", orderStatusOf(guest.sessionId))
        batch["items"].forEach {
            val itemRow = jdbc.queryForMap("SELECT created_by_user_id, device_id, confirmed_at FROM order_item WHERE id = ?", it["id"].asLong())
            assertEquals(staff.id, (itemRow["created_by_user_id"] as Number).toLong())
            assertNull(itemRow["device_id"])
            assertTrue(itemRow["confirmed_at"] != null)
        }

        assertEquals(1, stomp.on("/topic/kitchen/orders").size, "bếp nhận ngay vì đợt không cần xác nhận")
        assertTrue(stomp.on("/topic/session/${guest.sessionId}").any { it["change"].asText() == "BATCH_SUBMITTED" })
        val audit = api.auditRows("ORDER_ITEM_ADDED_BY_STAFF").single()
        assertEquals(staff.id, (audit["actor_user_id"] as Number).toLong())
        assertEquals("order_batch", audit["entity_type"])
        assertEquals(batch["id"].asLong(), (audit["entity_id"] as Number).toLong())
    }

    @Test
    fun `item history records STAFF as the orderer and the session stays unverified`() {
        val guest = guestAt("T01")

        val itemId = staffPlaceOk(guest.sessionId, phoSmall())["batch"]["items"][0]["id"].asLong()

        val first = history(itemId).first()
        assertEquals("STAFF", first["changed_by_type"])
        assertEquals(staff.id, (first["changed_by_user_id"] as Number).toLong())
        assertEquals("CONFIRMED", first["to_status"])
        assertNull(jdbc.queryForObject("SELECT verified_at FROM table_session WHERE id = ?", java.sql.Timestamp::class.java, guest.sessionId),
            "BR-S04 - chỉ việc xác nhận đợt của khách mới chứng minh phiên là thật")
    }

    @Test
    fun `staff can add a second batch to an order that already has a guest batch`() {
        val (guest, orderId, _) = tableWithStandardOrder()

        val result = staffPlaceOk(guest.sessionId, line("Chả giò", 3))

        assertEquals(2, result["batch"]["seq"].asInt())
        assertEquals(orderId, result["batch"]["orderId"].asLong())
        assertEquals(1L, count("SELECT count(*) FROM orders WHERE session_id = ?", guest.sessionId))
        assertEquals(159_000L + result["batch"]["items"][0]["lineTotal"].asLong(), result["billableTotal"].asLong())
    }

    @Test
    fun `placing the same clientRequestId again replays the batch without creating a new one`() {
        val guest = guestAt("T01")
        val requestId = UUID.randomUUID()
        val first = data(staffPlace(guest.sessionId, phoSmall(), clientRequestId = requestId).andExpect(status().isCreated))

        val again = data(staffPlace(guest.sessionId, phoSmall(), clientRequestId = requestId).andExpect(status().isOk))

        assertEquals(true, again["replayed"].asBoolean())
        assertEquals(first["batch"]["id"].asLong(), again["batch"]["id"].asLong())
        assertEquals(1L, count("SELECT count(*) FROM order_batch"))
        assertEquals(1L, count("SELECT count(*) FROM order_item"))
        assertEquals(1, api.auditRows("ORDER_ITEM_ADDED_BY_STAFF").size)
    }

    @Test
    fun `a clientRequestId already used by the guest is a conflict for staff`() {
        val guest = guestAt("T01")
        val requestId = UUID.randomUUID()
        place(guest, phoSmall(), clientRequestId = requestId).andExpect(status().isCreated)

        staffPlace(guest.sessionId, phoSmall(), clientRequestId = requestId).isError(409, "DUPLICATE_CLIENT_REQUEST")

        assertEquals(1L, count("SELECT count(*) FROM order_batch"))
    }

    @Test
    fun `staff is not limited by the guest rate limit or the pending batch limit`() {
        jdbc.update("UPDATE restaurant_settings SET order_rate_limit_per_minute = 1, max_pending_batches_per_session = 1")
        val guest = guestAt("T01")

        repeat(4) { staffPlace(guest.sessionId, phoSmall()).andExpect(status().isCreated) }

        assertEquals(4L, count("SELECT count(*) FROM order_batch WHERE submitted_by_type = 'STAFF'"))
    }

    @Test
    fun `staff does not need the table PIN and the order confirmation mode is irrelevant`() {
        val guest = guestAt("T01")
        jdbc.update("UPDATE restaurant_settings SET require_table_pin = true, order_confirmation_mode = 'ALL'")

        val batch = staffPlaceOk(guest.sessionId, phoSmall())["batch"]

        assertEquals("ACCEPTED", batch["status"].asText())
        assertEquals("CONFIRMED", batch["items"][0]["status"].asText())
    }

    @Test
    fun `manager can also place for a guest`() {
        val guest = guestAt("T01")
        staffPlaceOk(guest.sessionId, phoSmall(), token = manager.accessToken)
        assertEquals(manager.id, (jdbc.queryForMap("SELECT submitted_by_user_id FROM order_batch")["submitted_by_user_id"] as Number).toLong())
    }

    @Test
    fun `validation - empty items, bad quantity, missing option, unknown product, sold out product`() {
        val guest = guestAt("T01")

        staffPlace(guest.sessionId).isError(400, "VALIDATION_FAILED")
        staffPlace(guest.sessionId, phoSmall(0)).isError(400, "VALIDATION_FAILED")
        staffPlace(guest.sessionId, phoSmall(100)).isError(400, "VALIDATION_FAILED")
        staffPlace(guest.sessionId, line(pho, 1)).isError(422, "INVALID_OPTION_SELECTION")
        staffPlace(guest.sessionId, mapOf("productId" to 999_999, "quantity" to 1, "optionItemIds" to emptyList<Long>())).andExpect(status().is4xxClientError)
        val id = productId("Chả giò")
        api.patch("/api/v1/products/$id/status", kitchen.accessToken, mapOf("status" to "SOLD_OUT", "version" to version("product", id))).andExpect(status().isOk)
        staffPlace(guest.sessionId, line("Chả giò")).isError(409, "PRODUCT_NOT_AVAILABLE")

        assertEquals(0L, count("SELECT count(*) FROM order_batch"))
    }

    @Test
    fun `a session that is billing or closed no longer accepts staff orders`() {
        val (billingGuest, orderId, _) = tableWithStandardOrder("T01")
        createBillOk(orderId)
        staffPlace(billingGuest.sessionId, phoSmall()).isError(409, "SESSION_NOT_OPEN")

        val (closedGuest, _, _) = paidAndClosed("T02")
        staffPlace(closedGuest.sessionId, phoSmall()).isError(409, "SESSION_NOT_OPEN")
    }

    @Test
    fun `an unknown session is 404 and only floor staff can place`() {
        val guest = guestAt("T01")

        staffPlace(999_999, phoSmall()).isError(404, "RESOURCE_NOT_FOUND")
        staffPlace(guest.sessionId, phoSmall(), token = kitchen.accessToken).isError(403, "PERMISSION_DENIED")
        staffPlace(guest.sessionId, phoSmall(), token = admin.accessToken).isError(403, "PERMISSION_DENIED")
        staffPlace(guest.sessionId, phoSmall(), token = guest.token).isError(403, "PERMISSION_DENIED")
        staffPlace(guest.sessionId, phoSmall(), token = null).isError(401, "UNAUTHENTICATED")
        assertEquals(0L, count("SELECT count(*) FROM order_batch"))
    }

    // ================================================================================================ Đổi số lượng

    @Test
    fun `quantity change on a CONFIRMED item recalculates the line total, audits and notifies the kitchen`() {
        val (guest, _, items) = tableWithStandardOrder()
        val pho = items[0]
        stomp.clear()

        val result = data(changeQuantity(pho, 3, itemVersion(pho)).andExpect(status().isOk))

        assertEquals(3, result["item"]["quantity"].asInt())
        assertEquals(195_000L, result["item"]["lineTotal"].asLong())
        assertEquals(195_000L, long(jdbc.queryForMap("SELECT line_total FROM order_item WHERE id = ?", pho), "line_total"))
        val audit = api.auditRows("ORDER_ITEM_QUANTITY_CHANGED").single()
        assertEquals("order_item", audit["entity_type"])
        assertEquals(pho, (audit["entity_id"] as Number).toLong())
        assertEquals(2, api.jsonOf(audit["old_value"])["quantity"].asInt())
        assertEquals(3, api.jsonOf(audit["new_value"])["quantity"].asInt())
        assertEquals(1, stomp.on("/topic/kitchen/orders").size)
        assertTrue(stomp.on("/topic/session/${guest.sessionId}").any { it["change"].asText() == "ITEM_QUANTITY_CHANGED" })
    }

    @Test
    fun `quantity change works on a PENDING item and the kitchen is not told about an item it has not received`() {
        val guest = guestAt("T01")
        val (_, itemId) = placePho(guest)
        assertEquals("PENDING", itemStatus(itemId))
        stomp.clear()

        changeQuantity(itemId, 5, itemVersion(itemId)).andExpect(status().isOk)

        assertEquals(5, jdbc.queryForObject("SELECT quantity FROM order_item WHERE id = ?", Int::class.java, itemId))
        assertEquals(0, stomp.on("/topic/kitchen/orders").size)
    }

    @Test
    fun `a stale version is a concurrent modification and changes nothing`() {
        val (_, _, items) = tableWithStandardOrder()
        val current = itemVersion(items[0])

        changeQuantity(items[0], 3, current).andExpect(status().isOk)
        changeQuantity(items[0], 4, current).isError(409, "CONCURRENT_MODIFICATION")

        assertEquals(3, jdbc.queryForObject("SELECT quantity FROM order_item WHERE id = ?", Int::class.java, items[0]))
    }

    @Test
    fun `quantity must be between 1 and the configured maximum and the same quantity is a no-op`() {
        val (_, _, items) = tableWithStandardOrder()
        jdbc.update("UPDATE restaurant_settings SET max_item_quantity = 5")
        val v = itemVersion(items[0])

        changeQuantity(items[0], 0, v).isError(400, "VALIDATION_FAILED")
        changeQuantity(items[0], 6, v).isError(400, "VALIDATION_FAILED")
        changeQuantity(items[0], null, v).isError(400, "VALIDATION_FAILED")
        changeQuantity(items[0], 3, null).isError(400, "VALIDATION_FAILED")
        changeQuantity(items[0], 5, v).andExpect(status().isOk)
        val after = itemVersion(items[0])
        changeQuantity(items[0], 5, after).andExpect(status().isOk)

        assertEquals(after, itemVersion(items[0]), "không đổi gì thì không tăng version")
        assertEquals(1, api.auditRows("ORDER_ITEM_QUANTITY_CHANGED").size)
    }

    @Test
    fun `an item already being prepared cannot change quantity`() {
        val (_, _, items) = tableWithStandardOrder()
        advance(items[0], "PREPARING")

        changeQuantity(items[0], 1, itemVersion(items[0])).isError(409, "ORDER_ITEM_NOT_EDITABLE")

        assertEquals(2, jdbc.queryForObject("SELECT quantity FROM order_item WHERE id = ?", Int::class.java, items[0]))
    }

    @Test
    fun `a cancelled item or an order that is billing cannot change quantity`() {
        val (_, orderId, items) = tableWithStandardOrder()
        staffCancelItem(items[1]).andExpect(status().isOk)
        changeQuantity(items[1], 2, itemVersion(items[1])).isError(409, "ORDER_ITEM_NOT_EDITABLE")

        createBillOk(orderId)
        changeQuantity(items[0], 1, itemVersion(items[0])).isError(409, "ORDER_NOT_OPEN")
    }

    @Test
    fun `quantity change needs floor staff and an existing item`() {
        val (_, _, items) = tableWithStandardOrder()
        val v = itemVersion(items[0])

        changeQuantity(items[0], 1, v, token = kitchen.accessToken).isError(403, "PERMISSION_DENIED")
        changeQuantity(items[0], 1, v, token = null).isError(401, "UNAUTHENTICATED")
        changeQuantity(999_999, 1, 0).isError(404, "RESOURCE_NOT_FOUND")
        changeQuantity(items[0], 1, v, token = manager.accessToken).andExpect(status().isOk)
    }

    // ================================================================================================ Hủy trực tiếp

    @Test
    fun `staff cancels a PENDING item with a reason`() {
        val guest = guestAt("T01")
        val (batchId, itemId) = placePho(guest)
        assertEquals("PENDING", itemStatus(itemId))

        val result = data(staffCancelItem(itemId, "  Khách gọi nhầm  ").andExpect(status().isOk))

        assertEquals("CANCELLED", result["item"]["status"].asText())
        assertEquals("STAFF_CANCELLED", result["item"]["reasonCode"].asText())
        assertEquals(false, result["item"]["wasted"].asBoolean())
        assertEquals(batchStatus(batchId), result["batchStatus"].asText())
        assertEquals("Khách gọi nhầm", jdbc.queryForObject("SELECT reason_note FROM order_item WHERE id = ?", String::class.java, itemId))
        val h = history(itemId).last()
        assertEquals("STAFF", h["changed_by_type"])
        assertEquals(staff.id, (h["changed_by_user_id"] as Number).toLong())
        assertEquals("STAFF_CANCELLED", h["reason_code"])
        val audit = api.auditRows("ORDER_ITEM_CANCELLED_BY_STAFF").single()
        assertEquals("Khách gọi nhầm", audit["reason"])
        assertEquals(itemId, (audit["entity_id"] as Number).toLong())
        assertEquals(0, stomp.on("/topic/kitchen/orders").size, "món PENDING chưa tới bếp")
    }

    @Test
    fun `staff cancels a CONFIRMED item - the kitchen is told and the item is not wasted`() {
        val (guest, _, items) = tableWithStandardOrder()
        stomp.clear()

        staffCancelItem(items[1], "Hết món").andExpect(status().isOk)

        assertEquals("CANCELLED", itemStatus(items[1]))
        assertEquals(false, jdbc.queryForObject("SELECT wasted FROM order_item WHERE id = ?", Boolean::class.java, items[1]))
        assertEquals(1, stomp.on("/topic/kitchen/orders").size)
        assertTrue(stomp.on("/topic/session/${guest.sessionId}").any { it["change"].asText() == "ITEM_CANCELLED" })
    }

    @Test
    fun `cancelling every item of a staff batch closes the batch`() {
        val guest = guestAt("T01")
        val batch = staffPlaceOk(guest.sessionId, phoSmall())["batch"]
        val itemId = batch["items"][0]["id"].asLong()

        val result = data(staffCancelItem(itemId).andExpect(status().isOk))

        assertEquals(batchStatus(batch["id"].asLong()), result["batchStatus"].asText())
        assertTrue(result["batchStatus"].asText() != "ACCEPTED")
    }

    @Test
    fun `an item being prepared or ready still needs the manager approved cancel request`() {
        val (guest, _, items) = tableWithStandardOrder()
        advance(items[0], "PREPARING")
        advance(items[1], "READY")

        staffCancelItem(items[0]).isError(409, "ORDER_ITEM_STATE_INVALID")
        staffCancelItem(items[1]).isError(409, "ORDER_ITEM_STATE_INVALID")
        staffCancelItem(items[0], token = manager.accessToken).isError(409, "ORDER_ITEM_STATE_INVALID")

        assertEquals("PREPARING", itemStatus(items[0]))
        assertEquals("READY", itemStatus(items[1]))
        assertEquals(0, api.auditRows("ORDER_ITEM_CANCELLED_BY_STAFF").size)
        // Đường cũ vẫn dùng được: khách gửi yêu cầu hủy, Manager duyệt (BR-O06).
        api.post("/api/v1/guest/orders/items/${items[0]}/cancel-requests", guest.token, mapOf("reason" to "Đổi ý")).andExpect(status().isCreated)
    }

    @Test
    fun `served or already cancelled items cannot be cancelled again and the reason is required`() {
        val (_, _, items) = tableWithStandardOrder()
        advance(items[0], "SERVED")
        staffCancelItem(items[0]).isError(409, "ORDER_ITEM_STATE_INVALID")
        staffCancelItem(items[1]).andExpect(status().isOk)
        staffCancelItem(items[1]).isError(409, "ORDER_ITEM_STATE_INVALID")

        val (_, _, other) = tableWithStandardOrder("T02")
        staffCancelItem(other[0], reason = null).isError(400, "VALIDATION_FAILED")
        staffCancelItem(other[0], reason = "   ").isError(400, "VALIDATION_FAILED")
        staffCancelItem(other[0], reason = "x".repeat(1001)).isError(400, "VALIDATION_FAILED")
        assertEquals("CONFIRMED", itemStatus(other[0]))
    }

    @Test
    fun `an order that is billing no longer allows staff cancel`() {
        val (_, orderId, items) = tableWithStandardOrder()
        createBillOk(orderId)

        staffCancelItem(items[0]).isError(409, "ORDER_NOT_OPEN")

        assertEquals("CONFIRMED", itemStatus(items[0]))
    }

    @Test
    fun `staff cancel needs floor staff and an existing item`() {
        val (_, _, items) = tableWithStandardOrder()

        staffCancelItem(items[0], token = kitchen.accessToken).isError(403, "PERMISSION_DENIED")
        staffCancelItem(items[0], token = null).isError(401, "UNAUTHENTICATED")
        staffCancelItem(999_999).isError(404, "RESOURCE_NOT_FOUND")
        staffCancelItem(items[0], token = manager.accessToken).andExpect(status().isOk)
    }
}