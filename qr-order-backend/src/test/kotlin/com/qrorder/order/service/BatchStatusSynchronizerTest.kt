package com.qrorder.order.service

import com.qrorder.common.domain.RequesterType
import com.qrorder.common.time.DbClock
import com.qrorder.order.domain.BatchStatus
import com.qrorder.order.domain.OrderBatch
import com.qrorder.order.domain.OrderItem
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.StatusActor
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Đồng bộ trạng thái đợt theo trạng thái món (hàm thuần theo `now` truyền vào, không chạm database). */
class BatchStatusSynchronizerTest {

    private val now = OffsetDateTime.parse("2026-11-20T10:00:00+07:00")
    private val sync = BatchStatusSynchronizer(DbClock(Mockito.mock(JdbcTemplate::class.java)))

    private fun batch(status: BatchStatus = BatchStatus.PENDING) =
        OrderBatch(1, 1, 1, UUID.randomUUID(), true, RequesterType.GUEST).apply { this.status = status }

    private fun item() = OrderItem(1, 1, 1, "Món", 10_000, 0, 1, "device-0001")

    private fun OrderItem.confirm() = apply { changeStatus(OrderItemStatus.CONFIRMED, StatusActor.Staff(1), now) }
    private fun OrderItem.reject() = apply { changeStatus(OrderItemStatus.REJECTED, StatusActor.System, now, "OUT_OF_STOCK") }
    private fun OrderItem.cancel() = apply { changeStatus(OrderItemStatus.CANCELLED, StatusActor.Guest("device-0001"), now, "GUEST_CANCELLED") }
    private fun OrderItem.serve() = apply {
        changeStatus(OrderItemStatus.CONFIRMED, StatusActor.Staff(1), now)
        changeStatus(OrderItemStatus.PREPARING, StatusActor.Staff(2), now)
        changeStatus(OrderItemStatus.READY, StatusActor.Staff(2), now)
        changeStatus(OrderItemStatus.SERVED, StatusActor.Staff(3), now)
    }

    @Test
    fun `pending batch stays pending while any item is still pending`() {
        val b = batch()
        assertFalse(sync.sync(b, listOf(item(), item().reject()), now))
        assertEquals(BatchStatus.PENDING, b.status)
    }

    @Test
    fun `pending batch with every item rejected becomes REJECTED with the item reason`() {
        val b = batch()
        assertTrue(sync.sync(b, listOf(item().reject(), item().reject()), now))
        assertEquals(BatchStatus.REJECTED, b.status)
        assertEquals("OUT_OF_STOCK", b.reasonCode)
        assertNotNull(b.decidedAt)
    }

    @Test
    fun `pending batch whose items the guest cancelled becomes CANCELLED`() {
        val b = batch()
        assertTrue(sync.sync(b, listOf(item().cancel(), item().cancel()), now))
        assertEquals(BatchStatus.CANCELLED, b.status)
    }

    @Test
    fun `accepted batch completes when every item is final and one was served`() {
        val b = batch(BatchStatus.ACCEPTED)
        assertFalse(sync.sync(b, listOf(item().confirm(), item().serve()), now), "còn món CONFIRMED")
        assertTrue(sync.sync(b, listOf(item().confirm().cancel(), item().serve()), now))
        assertEquals(BatchStatus.COMPLETED, b.status)
        assertNotNull(b.completedAt)
    }

    @Test
    fun `accepted batch where nothing was served closes as REJECTED or CANCELLED`() {
        val rejected = batch(BatchStatus.PARTIALLY_ACCEPTED)
        assertTrue(sync.sync(rejected, listOf(item().confirm().reject(), item().reject()), now))
        assertEquals(BatchStatus.REJECTED, rejected.status)
        assertEquals("OUT_OF_STOCK", rejected.reasonCode)

        val cancelled = batch(BatchStatus.ACCEPTED)
        assertTrue(sync.sync(cancelled, listOf(item().confirm().cancel()), now))
        assertEquals(BatchStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `final batches and empty item lists are left alone`() {
        assertFalse(sync.sync(batch(BatchStatus.COMPLETED), listOf(item().serve()), now))
        assertFalse(sync.sync(batch(), emptyList(), now))
    }
}
