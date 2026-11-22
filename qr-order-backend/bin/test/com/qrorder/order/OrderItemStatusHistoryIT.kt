package com.qrorder.order

import com.qrorder.auth.domain.UserRole
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.order.domain.OrderItem
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.OrderItemStatusHistory
import com.qrorder.order.domain.StatusActor
import com.qrorder.support.DbFixtures
import com.qrorder.support.IntegrationTestBase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Quy ước DATABASE_DESIGN.md §5.3 (12) / DB-14: mỗi lần đổi status món phải ghi đủ 3 cột người
 * thao tác để trigger `trg_order_item_status_history` ghi đúng người. Entity OrderItem chỉ cho đổi
 * trạng thái qua changeStatus(target, actor, ...), nên quy ước được bảo đảm ở tầng domain.
 * Tương ứng OH-01..OH-15, BIL-10 của tools/schema-test/test_schema.py.
 */
class OrderItemStatusHistoryIT : IntegrationTestBase() {

    private var staffId = 0L
    private var kitchenId = 0L
    private var sessionId = 0L
    private var orderId = 0L
    private var batchId = 0L

    @BeforeEach
    fun setUp() {
        staffId = fx.user("waiter", UserRole.STAFF)
        kitchenId = fx.user("chef", UserRole.KITCHEN)
        fx.openSessionWithOrder("T10").let { (s, o) -> sessionId = s; orderId = o }
        batchId = fx.addBatch(sessionId, orderId, 1, status = "PENDING")
    }

    /** DEC-02: phiên có đúng 1 Order; mọi món nằm trong các đợt của Order đó. */
    private fun newGuestItem(deviceId: String = "device-0001"): Long = tx.execute {
        OrderItem(
            orderId = orderId, batchId = batchId, productId = fx.productId(), productName = DbFixtures.PHO,
            unitPrice = 65_000, optionExtraPerUnit = 0, quantity = 2, deviceId = deviceId,
        ).also { em.persist(it); em.flush() }.id
    }!!

    private fun change(itemId: Long, target: OrderItemStatus, actor: StatusActor, reason: String? = null) =
        tx.executeWithoutResult { em.find(OrderItem::class.java, itemId).changeStatus(target, actor, OffsetDateTime.now(), reason) }

    private fun history(itemId: Long): List<OrderItemStatusHistory> =
        em.createQuery(
            "select h from OrderItemStatusHistory h where h.orderItemId = :id order by h.id",
            OrderItemStatusHistory::class.java,
        ).setParameter("id", itemId).resultList

    @Test
    fun `full lifecycle is recorded with the right actor for every transition`() {
        val itemId = newGuestItem()
        assertEquals(130_000L, fx.count("SELECT line_total FROM order_item WHERE id = ?", itemId))

        change(itemId, OrderItemStatus.CONFIRMED, StatusActor.Staff(staffId))
        change(itemId, OrderItemStatus.PREPARING, StatusActor.Staff(kitchenId))
        change(itemId, OrderItemStatus.READY, StatusActor.Staff(kitchenId))
        change(itemId, OrderItemStatus.SERVED, StatusActor.Staff(staffId))

        val rows = history(itemId).map { listOf(it.fromStatus, it.toStatus, it.changedByType.name, it.changedByUserId, it.deviceId) }
        assertEquals(
            listOf(
                listOf(null, OrderItemStatus.PENDING, "GUEST", null, "device-0001"),
                listOf(OrderItemStatus.PENDING, OrderItemStatus.CONFIRMED, "STAFF", staffId, null),
                listOf(OrderItemStatus.CONFIRMED, OrderItemStatus.PREPARING, "STAFF", kitchenId, null),
                listOf(OrderItemStatus.PREPARING, OrderItemStatus.READY, "STAFF", kitchenId, null),
                listOf(OrderItemStatus.READY, OrderItemStatus.SERVED, "STAFF", staffId, null),
            ),
            rows,
        )
    }

    @Test
    fun `guest cancel and system reject record GUEST device and SYSTEM, previous actor is never reused`() {
        val guestCancelled = newGuestItem()
        change(guestCancelled, OrderItemStatus.CONFIRMED, StatusActor.Staff(staffId))
        change(guestCancelled, OrderItemStatus.CANCELLED, StatusActor.Guest("device-0002"), "CUSTOMER_CANCELLED")
        val last = history(guestCancelled).last()
        assertEquals("GUEST", last.changedByType.name)
        assertEquals(null, last.changedByUserId, "Không được ghi lại Staff của lần trước (DB-14)")
        assertEquals("device-0002", last.deviceId)
        assertEquals("CUSTOMER_CANCELLED", last.reasonCode)

        val soldOut = newGuestItem()
        change(soldOut, OrderItemStatus.REJECTED, StatusActor.System, "OUT_OF_STOCK")
        val sys = history(soldOut).last()
        assertEquals("SYSTEM", sys.changedByType.name)
        assertEquals(null, sys.deviceId)
    }

    @Test
    fun `rejecting a dish that is being cooked marks it wasted (BR-O06)`() {
        val itemId = newGuestItem()
        change(itemId, OrderItemStatus.CONFIRMED, StatusActor.Staff(staffId))
        change(itemId, OrderItemStatus.PREPARING, StatusActor.Staff(kitchenId))
        change(itemId, OrderItemStatus.REJECTED, StatusActor.Staff(kitchenId), "OUT_OF_STOCK")

        assertTrue(jdbc.queryForObject("SELECT wasted FROM order_item WHERE id = ?", Boolean::class.java, itemId)!!)
    }

    @Test
    fun `invalid transition is rejected by the entity and by the trigger with the same error code`() {
        val itemId = newGuestItem()

        val domainError = assertFailsWith<BusinessException> {
            change(itemId, OrderItemStatus.READY, StatusActor.Staff(kitchenId))
        }
        assertEquals(ErrorCode.INVALID_ORDER_ITEM_TRANSITION, domainError.errorCode)

        // Bỏ qua tầng domain (ghi SQL trực tiếp): trigger là hàng rào cuối (DB-08).
        expectDbError(ErrorCode.INVALID_ORDER_ITEM_TRANSITION) {
            jdbc.update(
                "UPDATE order_item SET status = 'READY', confirmed_at = now(), preparing_at = now(), ready_at = now() WHERE id = ?",
                itemId,
            )
        }
        assertEquals(1, history(itemId).size, "Chuyển sai không được ghi lịch sử")
    }

    @Test
    fun `cancelling a dish while the Order is LOCKED fails with ORDER_NOT_OPEN (BR-O12)`() {
        val itemId = newGuestItem()
        tx.executeWithoutResult {
            jdbc.update("UPDATE table_session SET status = 'BILLING' WHERE id = ?", sessionId)
            jdbc.update("UPDATE orders SET status = 'LOCKED' WHERE id = ?", orderId)
        }

        expectDbError(ErrorCode.ORDER_NOT_OPEN) {
            change(itemId, OrderItemStatus.CANCELLED, StatusActor.Guest("device-0001"), "CUSTOMER_CANCELLED")
        }
    }

    @Test
    fun `a session can hold only one Order (DEC-02, uq_orders_session)`() {
        expectDbError(ErrorCode.ORDER_ALREADY_EXISTS) {
            jdbc.update("INSERT INTO orders (session_id) VALUES (?)", sessionId)
        }
        // Đợt thứ 2 của cùng Order với clientRequestId mới là hợp lệ; trùng clientRequestId thì bị chặn (BR-O04).
        fx.addBatch(sessionId, orderId, 2)
        val existing = jdbc.queryForObject("SELECT client_request_id::text FROM order_batch WHERE id = ?", String::class.java, batchId)
        expectDbError(ErrorCode.DUPLICATE_CLIENT_REQUEST) {
            jdbc.update(
                """INSERT INTO order_batch (order_id, session_id, seq, client_request_id, requires_confirmation, submitted_by_type, device_id)
                   VALUES (?, ?, 3, ?::uuid, true, 'GUEST', 'device-0001')""",
                orderId, sessionId, existing,
            )
        }
    }
}
