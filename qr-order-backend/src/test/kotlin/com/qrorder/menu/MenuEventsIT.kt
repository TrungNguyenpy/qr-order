package com.qrorder.menu

import com.qrorder.menu.domain.ProductStatus
import com.qrorder.menu.event.MenuChangeKind
import com.qrorder.menu.event.MenuChangedEvent
import com.qrorder.menu.event.MenuEntityType
import com.qrorder.menu.event.ProductSoldOutEvent
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.MenuIntegrationTestBase
import com.qrorder.support.RecordingEventListener
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Sự kiện menu:
 * - `/topic/menu` chỉ được gửi SAU KHI transaction commit (@TransactionalEventListener AFTER_COMMIT);
 *   rollback thì không gửi; eventId tăng dần; nội dung không lộ thông tin nhân viên.
 * - BR-O13 (GĐ4): ProductSoldOutEvent phát trong transaction đổi SOLD_OUT; listener của module order REJECTED
 *   (OUT_OF_STOCK, SYSTEM) các món PENDING/CONFIRMED của Order OPEN cùng commit (chi tiết ở OrderSoldOutIT).
 */
class MenuEventsIT : MenuIntegrationTestBase() {

    @Autowired lateinit var publisher: ApplicationEventPublisher

    private fun event(id: Long = 1) = MenuChangedEvent(MenuEntityType.PRODUCT, id, MenuChangeKind.UPDATED, 3, ProductStatus.AVAILABLE, 1000)

    @Test
    fun `menu topic message is sent only after commit`() {
        tx.executeWithoutResult {
            publisher.publishEvent(event())
            assertTrue(topic.messages.isEmpty(), "Chưa commit thì chưa gửi")
        }
        val message = topic.messages.single()
        assertEquals("MENU_CHANGED", message["type"].asText())
        assertEquals("PRODUCT", message["entityType"].asText())
        assertEquals(1L, message["entityId"].asLong())
        assertEquals("UPDATED", message["change"].asText())
        assertEquals(3L, message["version"].asLong())
        assertEquals("AVAILABLE", message["productStatus"].asText())
        assertEquals(1000L, message["price"].asLong())
        assertTrue(message["occurredAt"].isTextual)
        assertTrue(message["eventId"].asLong() > 0)
    }

    @Test
    fun `no message when the transaction rolls back, and none for events published outside a transaction`() {
        tx.executeWithoutResult { status ->
            publisher.publishEvent(event())
            status.setRollbackOnly()
        }
        assertTrue(topic.messages.isEmpty())

        publisher.publishEvent(event())
        assertTrue(topic.messages.isEmpty(), "Không có transaction: listener AFTER_COMMIT không chạy (fallbackExecution = false)")
    }

    @Test
    fun `eventId increases across messages and messages carry no staff identity`() {
        val pho = productId("Phở bò tái")
        api.patch("/api/v1/products/$pho/status", kitchen.accessToken, mapOf("status" to "SOLD_OUT", "version" to 0)).andExpect(status().isOk)
        api.patch("/api/v1/products/$pho/status", kitchen.accessToken, mapOf("status" to "AVAILABLE", "version" to 1)).andExpect(status().isOk)
        createCategory("Món mới")
        val ids = topic.messages.map { it["eventId"].asLong() }
        assertEquals(3, ids.size)
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
        topic.messages.forEach { m ->
            assertTrue(m.fieldNames().asSequence().none { it.contains("user", ignoreCase = true) || it.contains("By") })
        }
    }

    @Test
    fun `failed API change (409) sends no menu message`() {
        val id = categoryId("Khai vị")
        api.put("/api/v1/categories/$id", admin.accessToken, mapOf("name" to "Mới", "displayOrder" to 2, "version" to 9))
            .isError(409, "CONCURRENT_MODIFICATION")
        assertTrue(topic.messages.isEmpty())
    }

    @Test
    fun `BR-O13 - the order module rejects PENDING and CONFIRMED items of OPEN orders in the same commit`() {
        val pho = productId("Phở bò tái")
        // Bàn T01: Order OPEN, một món PENDING (đợt chờ xác nhận) + một món CONFIRMED (đợt đã nhận).
        val (session1, order1) = fx.openSessionWithOrder("T01")
        val pendingBatch = fx.addBatch(session1, order1, 1, status = "PENDING")
        val pendingItem = jdbc.queryForObject(
            """INSERT INTO order_item (order_id, batch_id, product_id, product_name, unit_price, quantity, line_total, device_id)
               VALUES (?, ?, ?, 'Phở bò tái', 65000, 1, 65000, 'device-0001') RETURNING id""",
            Long::class.java, order1, pendingBatch, pho,
        )!!
        val acceptedBatch = fx.addBatch(session1, order1, 2)
        val confirmedItem = fx.addConfirmedItem(order1, acceptedBatch, 65_000)
        // Bàn T02: Order LOCKED (đang thanh toán) - BR-O12: món giữ nguyên.
        val (session2, order2) = fx.openSessionWithOrder("T02")
        val lockedItem = fx.addConfirmedItem(order2, fx.addBatch(session2, order2, 1), 65_000)
        tx.executeWithoutResult { jdbc.update("UPDATE orders SET status = 'LOCKED' WHERE id = ?", order2) }

        val soldOut = RecordingEventListener(ProductSoldOutEvent::class.java).register(eventMulticaster)
        try {
            api.patch("/api/v1/products/$pho/status", kitchen.accessToken, mapOf("status" to "SOLD_OUT", "version" to 0))
                .andExpect(status().isOk)
        } finally {
            soldOut.unregister(eventMulticaster)
        }

        assertTrue(soldOut.events.single().insideTransaction, "Sự kiện phát bên trong transaction đổi SOLD_OUT")
        fun itemStatus(id: Long) = jdbc.queryForObject("SELECT status FROM order_item WHERE id = ?", String::class.java, id)
        assertEquals("REJECTED", itemStatus(pendingItem))
        assertEquals("REJECTED", itemStatus(confirmedItem))
        assertEquals("CONFIRMED", itemStatus(lockedItem), "Order LOCKED: không tự từ chối (BR-O12)")
        assertEquals(
            2L,
            count(
                "SELECT count(*) FROM order_item_status_history WHERE to_status = 'REJECTED' AND changed_by_type = 'SYSTEM' AND reason_code = 'OUT_OF_STOCK'",
            ),
            "Lịch sử món ghi SYSTEM (BR-O09)",
        )
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM order_batch WHERE id = ?", String::class.java, pendingBatch))
        assertEquals("SOLD_OUT", jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String::class.java, pho))
    }
}
