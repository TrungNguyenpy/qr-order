package com.qrorder.order

import com.qrorder.auth.domain.Permission
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.OrderIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Hủy món (BR-O05/O06, DEC-06 A): khách hủy PENDING/CONFIRMED; món đang nấu phải qua yêu cầu hủy do Manager duyệt. */
class OrderCancellationIT : OrderIntegrationTestBase() {

    private fun cancel(guest: com.qrorder.support.GuestCtx, itemId: Long) =
        api.post("/api/v1/guest/orders/items/$itemId/cancel", guest.token, mapOf("note" to "Đổi ý"))

    private fun requestCancel(guest: com.qrorder.support.GuestCtx, itemId: Long) =
        api.post("/api/v1/guest/orders/items/$itemId/cancel-requests", guest.token, mapOf("reason" to "Gọi nhầm"))

    private fun toPreparing(guest: com.qrorder.support.GuestCtx): Long {
        val (_, itemId) = placeAndConfirm(guest, staff.accessToken)
        api.post("/api/v1/kitchen/items/$itemId/status", kitchen.accessToken, mapOf("status" to "PREPARING")).andExpect(status().isOk)
        return itemId
    }

    @Test
    fun `guest cancels PENDING and CONFIRMED items directly, batch follows, not wasted`() {
        val guest = guestAt("T01")
        val (batchId, pending) = placePho(guest)
        val r = data(cancel(guest, pending).andExpect(status().isOk))
        assertEquals("CANCELLED", r["item"]["status"].asText())
        assertEquals("CANCELLED", batchStatus(batchId))
        assertEquals("GUEST_CANCELLED", history(pending).last()["reason_code"])
        assertEquals("GUEST", history(pending).last()["changed_by_type"])
        assertEquals(false, jdbc.queryForObject("SELECT wasted FROM order_item WHERE id = ?", Boolean::class.java, pending))
        assertEquals(0L, data(api.get("/api/v1/guest/orders", guest.token))["billableTotal"].asLong())

        val (_, confirmed) = placeAndConfirm(guest, staff.accessToken)
        cancel(guest, confirmed).andExpect(status().isOk)
        assertEquals("CANCELLED", itemStatus(confirmed))
        assertTrue(stomp.on("/topic/kitchen/orders").size >= 2, "Bếp biết món CONFIRMED đã bị hủy")
        cancel(guest, confirmed).isError(409, "ORDER_ITEM_STATE_INVALID")
    }

    @Test
    fun `PREPARING cannot be cancelled directly, guest files one request, duplicates are refused`() {
        val guest = guestAt("T01")
        val itemId = toPreparing(guest)
        cancel(guest, itemId).isError(409, "ORDER_ITEM_STATE_INVALID")
        val created = data(requestCancel(guest, itemId).andExpect(status().isCreated))
        assertEquals("PENDING", created["status"].asText())
        assertEquals("PREPARING", created["itemStatusAtRequest"].asText())
        requestCancel(guest, itemId).isError(409, "CANCEL_REQUEST_ALREADY_PENDING")
        assertEquals("PREPARING", itemStatus(itemId))
        assertTrue(stomp.on("/topic/staff/orders").any { it["change"].asText() == "CANCEL_REQUESTED" })
        // Món chưa vào bếp thì không cần yêu cầu hủy.
        val (_, pending) = placePho(guest)
        requestCancel(guest, pending).isError(409, "CANCEL_REQUEST_NOT_ALLOWED")
    }

    @Test
    fun `manager approves - item CANCELLED and wasted, audit written, request decided`() {
        val guest = guestAt("T01")
        val itemId = toPreparing(guest)
        val requestId = data(requestCancel(guest, itemId))["id"].asLong()
        val queue = data(api.get("/api/v1/orders/cancel-requests", manager.accessToken).andExpect(status().isOk))
        assertEquals(listOf(requestId), queue.map { it["id"].asLong() })
        assertEquals("T01", queue[0]["tableCode"].asText())

        val approved = data(api.post("/api/v1/orders/cancel-requests/$requestId/approve", manager.accessToken, mapOf("note" to "Đồng ý")).andExpect(status().isOk))
        assertEquals("APPROVED", approved["status"].asText())
        assertEquals("CANCELLED", itemStatus(itemId))
        assertEquals(true, jdbc.queryForObject("SELECT wasted FROM order_item WHERE id = ?", Boolean::class.java, itemId), "BR-O06")
        assertEquals("CANCEL_REQUEST_APPROVED", history(itemId).last()["reason_code"])
        val audit = api.auditRows("ITEM_CANCEL_APPROVED").single()
        assertEquals(manager.id, (audit["actor_user_id"] as Number).toLong())
        assertNotNull(audit["new_value"])
        api.post("/api/v1/orders/cancel-requests/$requestId/approve", manager.accessToken, null).isError(409, "CANCEL_REQUEST_NOT_PENDING")
        assertEquals(0, data(api.get("/api/v1/orders/cancel-requests", manager.accessToken)).size())
    }

    @Test
    fun `manager denies with a mandatory reason - item stays PREPARING, audit written`() {
        val guest = guestAt("T01")
        val itemId = toPreparing(guest)
        val requestId = data(requestCancel(guest, itemId))["id"].asLong()
        api.post("/api/v1/orders/cancel-requests/$requestId/deny", manager.accessToken, mapOf("reason" to "  ")).isError(400, "VALIDATION_FAILED")
        val denied = data(api.post("/api/v1/orders/cancel-requests/$requestId/deny", manager.accessToken, mapOf("reason" to "Món đã xong")).andExpect(status().isOk))
        assertEquals("DENIED", denied["status"].asText())
        assertEquals("PREPARING", itemStatus(itemId))
        assertEquals(1, api.auditRows("ITEM_CANCEL_DENIED").size)
        requestCancel(guest, itemId).andExpect(status().isCreated) // được gửi lại sau khi bị từ chối
    }

    @Test
    fun `approval needs ITEM_CANCEL_APPROVE - staff is refused until granted`() {
        val guest = guestAt("T01")
        val itemId = toPreparing(guest)
        val requestId = data(requestCancel(guest, itemId))["id"].asLong()
        api.get("/api/v1/orders/cancel-requests", staff.accessToken).isError(403, "PERMISSION_DENIED")
        api.post("/api/v1/orders/cancel-requests/$requestId/approve", staff.accessToken, null).isError(403, "PERMISSION_DENIED")
        api.post("/api/v1/orders/cancel-requests/$requestId/approve", kitchen.accessToken, null).andExpect(status().isForbidden)
        fx.grant(staff.id, Permission.ITEM_CANCEL_APPROVE, admin.id)
        api.post("/api/v1/orders/cancel-requests/$requestId/approve", staff.accessToken, null).andExpect(status().isOk)
        api.post("/api/v1/orders/cancel-requests/999999/approve", manager.accessToken, null).isError(404, "RESOURCE_NOT_FOUND")
    }

    @Test
    fun `serving or kitchen rejecting an item auto-denies its pending request`() {
        val guest = guestAt("T01")
        val served = toPreparing(guest)
        val rejected = toPreparing(guest)
        val r1 = data(requestCancel(guest, served))["id"].asLong()
        val r2 = data(requestCancel(guest, rejected))["id"].asLong()
        api.post("/api/v1/kitchen/items/$served/status", kitchen.accessToken, mapOf("status" to "READY")).andExpect(status().isOk)
        api.post("/api/v1/orders/items/$served/serve", staff.accessToken, null).andExpect(status().isOk)
        api.post("/api/v1/kitchen/items/$rejected/reject", kitchen.accessToken, mapOf("reasonCode" to "KITCHEN_ERROR")).andExpect(status().isOk)
        listOf(r1, r2).forEach {
            assertEquals("DENIED", jdbc.queryForObject("SELECT status FROM item_cancel_request WHERE id = ?", String::class.java, it))
        }
        assertEquals(0, data(api.get("/api/v1/orders/cancel-requests", manager.accessToken)).size())
    }

    @Test
    fun `another table's guest cannot touch the item, a LOCKED order refuses cancellation`() {
        val owner = guestAt("T01")
        val stranger = guestAt("T02")
        val (_, itemId) = placePho(owner)
        cancel(stranger, itemId).isError(404, "RESOURCE_NOT_FOUND")
        requestCancel(stranger, itemId).isError(404, "RESOURCE_NOT_FOUND")
        assertEquals("PENDING", itemStatus(itemId))
        jdbc.update("UPDATE orders SET status = 'LOCKED' WHERE session_id = ?", owner.sessionId)
        cancel(owner, itemId).isError(409, "ORDER_NOT_OPEN")
        assertEquals("PENDING", itemStatus(itemId))
    }
}
