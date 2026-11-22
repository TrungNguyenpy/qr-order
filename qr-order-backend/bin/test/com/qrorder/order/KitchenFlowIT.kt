package com.qrorder.order

import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.OrderIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Bếp (KDS) và phục vụ: CONFIRMED -> PREPARING -> READY -> SERVED, bếp từ chối món (BR-O06), lịch sử + real-time. */
class KitchenFlowIT : OrderIntegrationTestBase() {

    private fun kitchenStatus(itemId: Long, target: String) =
        api.post("/api/v1/kitchen/items/$itemId/status", kitchen.accessToken, mapOf("status" to target))

    private fun wasted(itemId: Long) = jdbc.queryForObject("SELECT wasted FROM order_item WHERE id = ?", Boolean::class.java, itemId)!!

    @Test
    fun `KDS lists only CONFIRMED and PREPARING items oldest first, filterable by status`() {
        val a = guestAt("T01")
        val b = guestAt("T02")
        val (_, itemA) = placeAndConfirm(a, staff.accessToken)
        val (_, itemB) = placeAndConfirm(b, staff.accessToken)
        placePho(guestAt("T03")) // PENDING: chưa vào bếp
        kitchenStatus(itemB, "PREPARING").andExpect(status().isOk)

        val all = data(api.get("/api/v1/kitchen/items", kitchen.accessToken).andExpect(status().isOk))
        assertEquals(setOf(itemA, itemB), all.map { it["itemId"].asLong() }.toSet())
        assertEquals("T01", all.first { it["itemId"].asLong() == itemA }["tableCode"].asText())
        assertEquals("Phở bò tái", all[0]["productName"].asText())
        val preparing = data(api.get("/api/v1/kitchen/items?status=PREPARING", kitchen.accessToken))
        assertEquals(listOf(itemB), preparing.map { it["itemId"].asLong() })
        // Quản lý cũng xem được; Staff/khách/chưa đăng nhập thì không.
        api.get("/api/v1/kitchen/items", manager.accessToken).andExpect(status().isOk)
        api.get("/api/v1/kitchen/items", staff.accessToken).isError(403, "PERMISSION_DENIED")
        api.get("/api/v1/kitchen/items", a.token).isError(403, "PERMISSION_DENIED")
        api.get("/api/v1/kitchen/items", null).isError(401, "UNAUTHENTICATED")
    }

    @Test
    fun `full flow CONFIRMED to SERVED stamps timestamps, history actors, batch COMPLETED and notifies`() {
        val guest = guestAt("T01")
        val (batchId, itemId) = placeAndConfirm(guest, staff.accessToken)

        val prep = data(kitchenStatus(itemId, "PREPARING").andExpect(status().isOk))
        assertEquals("PREPARING", prep["item"]["status"].asText())
        assertEquals("ACCEPTED", prep["batchStatus"].asText())
        val ready = data(kitchenStatus(itemId, "READY").andExpect(status().isOk))
        assertEquals("READY", ready["item"]["status"].asText())
        assertEquals(1, data(api.get("/api/v1/orders/items/ready", staff.accessToken)).size())
        val served = data(api.post("/api/v1/orders/items/$itemId/serve", staff.accessToken, null).andExpect(status().isOk))
        assertEquals("SERVED", served["item"]["status"].asText())
        assertEquals("COMPLETED", served["batchStatus"].asText())
        assertEquals("COMPLETED", batchStatus(batchId))
        assertEquals(0, data(api.get("/api/v1/orders/items/ready", staff.accessToken)).size())

        val row = jdbc.queryForMap("SELECT preparing_at, ready_at, served_at, closed_at FROM order_item WHERE id = ?", itemId)
        assertNotNull(row["preparing_at"]); assertNotNull(row["ready_at"]); assertNotNull(row["served_at"])
        val h = history(itemId)
        assertEquals(listOf("PENDING", "CONFIRMED", "PREPARING", "READY", "SERVED"), h.map { it["to_status"] })
        assertEquals(listOf("GUEST", "STAFF", "STAFF", "STAFF", "STAFF"), h.map { it["changed_by_type"] })
        assertEquals(kitchen.id, h[2]["changed_by_user_id"])
        assertEquals(staff.id, h[4]["changed_by_user_id"])

        val changes = stomp.on("/topic/session/${guest.sessionId}").map { it["change"].asText() }
        assertTrue(changes.count { it == "ITEM_STATUS_CHANGED" } >= 3, changes.toString())
        assertTrue(stomp.on("/topic/staff/ready").isNotEmpty(), "Phục vụ nhận thông báo món READY")
    }

    @Test
    fun `invalid transitions are refused with 409 and nothing changes`() {
        val guest = guestAt("T01")
        val (_, itemId) = placeAndConfirm(guest, staff.accessToken)
        kitchenStatus(itemId, "READY").andExpect(status().isConflict)           // bỏ qua PREPARING
        kitchenStatus(itemId, "CONFIRMED").isError(400, "VALIDATION_FAILED")    // bếp chỉ PREPARING/READY
        api.post("/api/v1/orders/items/$itemId/serve", staff.accessToken, null).andExpect(status().isConflict) // chưa READY
        assertEquals("CONFIRMED", itemStatus(itemId))
        kitchenStatus(itemId, "PREPARING").andExpect(status().isOk)
        kitchenStatus(itemId, "PREPARING").andExpect(status().isConflict)
        kitchenStatus(999999, "PREPARING").isError(404, "RESOURCE_NOT_FOUND")
        // PENDING chưa được bếp nhận.
        val (_, pendingItem) = placePho(guestAt("T02"))
        kitchenStatus(pendingItem, "PREPARING").andExpect(status().isConflict)
    }

    @Test
    fun `kitchen reject - CONFIRMED is not wasted, PREPARING is wasted, READY cannot be rejected, reason is mandatory`() {
        val guest = guestAt("T01")
        val (_, confirmed) = placeAndConfirm(guest, staff.accessToken)
        val (_, preparing) = placeAndConfirm(guest, staff.accessToken)
        val (_, ready) = placeAndConfirm(guest, staff.accessToken)
        kitchenStatus(preparing, "PREPARING").andExpect(status().isOk)
        kitchenStatus(ready, "PREPARING").andExpect(status().isOk)
        kitchenStatus(ready, "READY").andExpect(status().isOk)

        api.post("/api/v1/kitchen/items/$confirmed/reject", kitchen.accessToken, mapOf("reasonNote" to "x")).isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/kitchen/items/$ready/reject", kitchen.accessToken, mapOf("reasonCode" to "OUT_OF_STOCK")).andExpect(status().isConflict)

        val r1 = data(api.post("/api/v1/kitchen/items/$confirmed/reject", kitchen.accessToken, mapOf("reasonCode" to "OUT_OF_STOCK")).andExpect(status().isOk))
        assertEquals("REJECTED", r1["item"]["status"].asText())
        assertEquals(false, wasted(confirmed), "BR-O06: chưa chế biến thì không thất thoát")
        api.post("/api/v1/kitchen/items/$preparing/reject", kitchen.accessToken, mapOf("reasonCode" to "KITCHEN_ERROR", "reasonNote" to "Làm đổ")).andExpect(status().isOk)
        assertEquals(true, wasted(preparing), "BR-O06: đã chế biến thì wasted")
        assertEquals("KITCHEN_ERROR", history(preparing).last()["reason_code"])
        assertEquals(listOf("READY"), listOf(itemStatus(ready)))
    }

    @Test
    fun `roles - only kitchen or manager move items in the kitchen, only floor staff serve`() {
        val guest = guestAt("T01")
        val (_, itemId) = placeAndConfirm(guest, staff.accessToken)
        api.post("/api/v1/kitchen/items/$itemId/status", staff.accessToken, mapOf("status" to "PREPARING")).isError(403, "PERMISSION_DENIED")
        api.post("/api/v1/kitchen/items/$itemId/status", guest.token, mapOf("status" to "PREPARING")).isError(403, "PERMISSION_DENIED")
        api.post("/api/v1/kitchen/items/$itemId/reject", staff.accessToken, mapOf("reasonCode" to "OUT_OF_STOCK")).isError(403, "PERMISSION_DENIED")
        kitchenStatus(itemId, "PREPARING").andExpect(status().isOk)
        kitchenStatus(itemId, "READY").andExpect(status().isOk)
        api.post("/api/v1/orders/items/$itemId/serve", kitchen.accessToken, null).isError(403, "PERMISSION_DENIED")
        api.post("/api/v1/orders/items/$itemId/serve", manager.accessToken, null).andExpect(status().isOk)
    }

    @Test
    fun `database trigger still refuses an illegal jump written behind the application's back`() {
        val guest = guestAt("T01")
        val (_, itemId) = placeAndConfirm(guest, staff.accessToken)
        val failure = runCatching { jdbc.update("UPDATE order_item SET status = 'SERVED' WHERE id = ?", itemId) }
        assertTrue(failure.isFailure, "fn_order_item_guard là hàng rào cuối của máy trạng thái")
        assertEquals("CONFIRMED", itemStatus(itemId))
    }
}
