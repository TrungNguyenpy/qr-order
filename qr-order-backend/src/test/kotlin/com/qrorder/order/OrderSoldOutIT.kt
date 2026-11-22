package com.qrorder.order

import com.qrorder.support.OrderIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BR-O13: đổi món sang SOLD_OUT tự REJECT món PENDING/CONFIRMED (OUT_OF_STOCK, SYSTEM) của Order còn OPEN. */
class OrderSoldOutIT : OrderIntegrationTestBase() {

    private fun soldOut(name: String) {
        val id = productId(name)
        val v = version("product", id)
        api.patch("/api/v1/products/$id/status", kitchen.accessToken, mapOf("status" to "SOLD_OUT", "version" to v)).andExpect(status().isOk)
    }

    @Test
    fun `sold-out rejects PENDING and CONFIRMED items across sessions but keeps PREPARING and other products`() {
        val a = guestAt("T01")
        val b = guestAt("T02")
        val (batchA, pendingA) = placePho(a)
        val (_, confirmedB) = placeAndConfirm(b, staff.accessToken)
        val (_, preparingB) = placeAndConfirm(b, staff.accessToken)
        api.post("/api/v1/kitchen/items/$preparingB/status", kitchen.accessToken, mapOf("status" to "PREPARING")).andExpect(status().isOk)
        val (_, otherProduct) = placeOk(a, line("Chả giò")).let { // "Chả giò": món không có option bắt buộc
            it["batches"].last()["id"].asLong() to it["batches"].last()["items"][0]["id"].asLong() }

        soldOut(pho)

        assertEquals("REJECTED", itemStatus(pendingA))
        assertEquals("REJECTED", itemStatus(confirmedB))
        assertEquals("PREPARING", itemStatus(preparingB), "Món đang nấu không bị ảnh hưởng")
        assertTrue(itemStatus(otherProduct) in setOf("PENDING", "CONFIRMED"))
        val h = history(pendingA).last()
        assertEquals("OUT_OF_STOCK", h["reason_code"])
        assertEquals("SYSTEM", h["changed_by_type"])
        assertEquals("REJECTED", batchStatus(batchA))
        val view = data(api.get("/api/v1/guest/orders", a.token))
        assertEquals("OUT_OF_STOCK", view["batches"][0]["items"][0]["reasonCode"].asText())
        assertTrue(stomp.on("/topic/session/${a.sessionId}").any { it["change"].asText() == "SYSTEM_REJECTED" })
        assertTrue(stomp.on("/topic/session/${b.sessionId}").any { it["change"].asText() == "SYSTEM_REJECTED" })
    }

    @Test
    fun `LOCKED orders are left alone (BR-O13)`() {
        val guest = guestAt("T01")
        val (_, itemId) = placeAndConfirm(guest, staff.accessToken)
        jdbc.update("UPDATE orders SET status = 'LOCKED' WHERE session_id = ?", guest.sessionId)
        soldOut(pho)
        assertEquals("CONFIRMED", itemStatus(itemId))
    }
}
