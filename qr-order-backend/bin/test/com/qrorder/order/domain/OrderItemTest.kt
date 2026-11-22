package com.qrorder.order.domain

import com.qrorder.common.domain.StatusActorType
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** Máy trạng thái món §9.1, BR-O06, BR-O08, BR-P18 và quy ước 3 cột người thao tác (DB-14). */
class OrderItemTest {

    private val now = OffsetDateTime.parse("2026-11-20T10:00:00+07:00")

    private fun item(quantity: Int = 2) = OrderItem(
        orderId = 1, batchId = 1, productId = 1, productName = "Phở bò tái",
        unitPrice = 65_000, optionExtraPerUnit = 10_000, quantity = quantity, deviceId = "device-0001",
    )

    @Test
    fun `line total follows BR-P02`() {
        assertEquals(150_000L, item().lineTotal)
    }

    @Test
    fun `transition table matches SRS 9_1 exactly`() {
        val expected = mapOf(
            "PENDING" to setOf("CONFIRMED", "REJECTED", "CANCELLED"),
            "CONFIRMED" to setOf("PREPARING", "CANCELLED", "REJECTED"),
            "PREPARING" to setOf("READY", "REJECTED", "CANCELLED"),
            "READY" to setOf("SERVED", "CANCELLED"),
            "SERVED" to emptySet(),
            "CANCELLED" to emptySet(),
            "REJECTED" to emptySet(),
        )
        OrderItemStatus.entries.forEach { from ->
            OrderItemStatus.entries.forEach { to ->
                assertEquals(to.name in expected.getValue(from.name), from.canTransitionTo(to), "$from -> $to")
            }
        }
    }

    @Test
    fun `billable statuses follow BR-P18`() {
        assertEquals(
            setOf(OrderItemStatus.CONFIRMED, OrderItemStatus.PREPARING, OrderItemStatus.READY, OrderItemStatus.SERVED),
            OrderItemStatus.entries.filter { it.isBillable }.toSet(),
        )
    }

    @Test
    fun `every change records all three actor columns and overwrites the previous actor`() {
        val item = item()
        item.changeStatus(OrderItemStatus.CONFIRMED, StatusActor.Staff(7), now)
        assertEquals(StatusActorType.STAFF, item.statusChangedByType)
        assertEquals(7L, item.statusChangedBy)
        assertNull(item.statusChangedByDeviceId)
        assertEquals(now, item.confirmedAt)

        item.changeStatus(OrderItemStatus.CANCELLED, StatusActor.Guest("device-0002"), now, "CUSTOMER_CANCELLED")
        assertEquals(StatusActorType.GUEST, item.statusChangedByType)
        assertNull(item.statusChangedBy, "Không được giữ user của lần trước")
        assertEquals("device-0002", item.statusChangedByDeviceId)
        assertEquals(now, item.closedAt)
        assertFalse(item.wasted)
    }

    @Test
    fun `cancel or reject while cooking is always wasted (BR-O06)`() {
        val item = item()
        item.changeStatus(OrderItemStatus.CONFIRMED, StatusActor.Staff(1), now)
        item.changeStatus(OrderItemStatus.PREPARING, StatusActor.Staff(2), now)
        item.changeStatus(OrderItemStatus.REJECTED, StatusActor.System, now, "OUT_OF_STOCK")
        assertTrue(item.wasted)
        assertEquals(StatusActorType.SYSTEM, item.statusChangedByType)
        assertNull(item.statusChangedBy)
        assertNotNull(item.preparingAt)
    }

    @Test
    fun `cancel and reject require a valid reason code (BR-O08)`() {
        listOf(null, "", "lowercase", "1BAD").forEach { reason ->
            val ex = assertFailsWith<BusinessException> {
                item().changeStatus(OrderItemStatus.CANCELLED, StatusActor.Guest("device-0001"), now, reason)
            }
            assertEquals(ErrorCode.INVALID_REASON_CODE, ex.errorCode)
        }
    }

    @Test
    fun `invalid transition is rejected with INVALID_ORDER_ITEM_TRANSITION and state is unchanged`() {
        val item = item()
        val ex = assertFailsWith<BusinessException> {
            item.changeStatus(OrderItemStatus.SERVED, StatusActor.Staff(1), now)
        }
        assertEquals(ErrorCode.INVALID_ORDER_ITEM_TRANSITION, ex.errorCode)
        assertEquals(OrderItemStatus.PENDING, item.status)
        assertNull(item.statusChangedByType)
    }

    @Test
    fun `changeQuantity recomputes the line total and only works while PENDING or CONFIRMED (BE-04)`() {
        val item = item(quantity = 2)
        item.changeQuantity(3)
        assertEquals(3, item.quantity)
        assertEquals(225_000L, item.lineTotal, "(65.000 + 10.000) x 3")
        item.changeStatus(OrderItemStatus.CONFIRMED, StatusActor.Staff(1), now)
        item.changeQuantity(1)
        assertEquals(75_000L, item.lineTotal)

        item.changeStatus(OrderItemStatus.PREPARING, StatusActor.Staff(1), now)
        val ex = assertFailsWith<BusinessException> { item.changeQuantity(5) }
        assertEquals(ErrorCode.ORDER_ITEM_NOT_EDITABLE, ex.errorCode)
        assertEquals(1, item.quantity)
        assertEquals(75_000L, item.lineTotal)
    }

    @Test
    fun `changeQuantity rejects a quantity below 1`() {
        assertFailsWith<IllegalArgumentException> { item().changeQuantity(0) }
    }
}
