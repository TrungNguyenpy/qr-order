package com.qrorder.order.service

import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.settings.OrderConfirmationMode
import com.qrorder.menu.domain.OptionGroup
import com.qrorder.menu.domain.OptionItem
import com.qrorder.menu.domain.SelectionType
import com.qrorder.order.domain.OrderItem
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.StatusActor
import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Quy tắc thuần của GĐ4: chế độ duyệt đợt (DEC-07 A), option (BR-O02/O03, BE-18), thất thu (BR-P15/P18). */
class OrderPoliciesTest {

    // ---------------------------------------------------------------------------- Chế độ duyệt đợt
    @Test
    fun `confirmation policy covers the three modes`() {
        // ALL (EVERY_BATCH): luôn cần xác nhận. NONE (AUTO_CONFIRM): không bao giờ.
        listOf(false, true).forEach { verified ->
            assertTrue(OrderConfirmationPolicy.requiresConfirmation(OrderConfirmationMode.ALL, verified))
            assertFalse(OrderConfirmationPolicy.requiresConfirmation(OrderConfirmationMode.NONE, verified))
        }
        // FIRST_BATCH_ONLY: chỉ khi phiên chưa được xác minh (đợt đầu chưa được Staff chấp nhận).
        assertTrue(OrderConfirmationPolicy.requiresConfirmation(OrderConfirmationMode.FIRST_BATCH_ONLY, sessionVerified = false))
        assertFalse(OrderConfirmationPolicy.requiresConfirmation(OrderConfirmationMode.FIRST_BATCH_ONLY, sessionVerified = true))
    }

    // ---------------------------------------------------------------------------- Option
    private var nextId = 1L

    private fun withId(entity: Any, id: Long) {
        val field = entity.javaClass.getDeclaredField("id")
        field.isAccessible = true
        field.set(entity, id)
    }

    private fun group(name: String, type: SelectionType, min: Int, max: Int?, vararg itemNames: String): EffectiveOptionGroup {
        val g = OptionGroup(name, type).apply {
            required = min >= 1
            minSelect = min.toShort()
            maxSelect = max?.toShort()
        }
        withId(g, nextId++)
        val items = itemNames.map { n -> OptionItem(requireNotNull(g.id), n, 1_000).also { withId(it, nextId++) } }
        return EffectiveOptionGroup(g, items)
    }

    private fun errorOf(block: () -> Unit): BusinessException = assertFailsWith<BusinessException> { block() }

    @Test
    fun `required single group needs exactly one selection`() {
        val size = group("Kích cỡ", SelectionType.SINGLE, 1, 1, "Nhỏ", "Vừa", "Lớn")
        val ids = size.items.map { requireNotNull(it.id) }
        assertEquals(1, OptionSelectionValidator.validate("Phở", listOf(size), listOf(ids[1])).size)
        assertEquals(ErrorCode.INVALID_OPTION_SELECTION, errorOf { OptionSelectionValidator.validate("Phở", listOf(size), emptyList()) }.errorCode)
        assertEquals(ErrorCode.INVALID_OPTION_SELECTION, errorOf { OptionSelectionValidator.validate("Phở", listOf(size), ids.take(2)) }.errorCode)
    }

    @Test
    fun `optional multiple group honours maxSelect and unlimited max`() {
        val topping = group("Topping", SelectionType.MULTIPLE, 0, 2, "A", "B", "C")
        val more = group("Thêm", SelectionType.MULTIPLE, 0, null, "X", "Y", "Z")
        val t = topping.items.map { requireNotNull(it.id) }
        val m = more.items.map { requireNotNull(it.id) }
        assertEquals(0, OptionSelectionValidator.validate("Trà", listOf(topping, more), emptyList()).size)
        assertEquals(2, OptionSelectionValidator.validate("Trà", listOf(topping, more), t.take(2)).size)
        assertEquals(3, OptionSelectionValidator.validate("Trà", listOf(topping, more), m).size)
        assertEquals(ErrorCode.INVALID_OPTION_SELECTION, errorOf { OptionSelectionValidator.validate("Trà", listOf(topping, more), t) }.errorCode)
    }

    @Test
    fun `option of another product, inactive or duplicated is rejected and hidden groups are never required (BE-18)`() {
        val size = group("Kích cỡ", SelectionType.SINGLE, 1, 1, "Nhỏ")
        val foreign = group("Của món khác", SelectionType.SINGLE, 0, 1, "Lạ")
        val foreignId = requireNotNull(foreign.items.single().id)
        assertEquals(
            ErrorCode.INVALID_OPTION_SELECTION,
            errorOf { OptionSelectionValidator.validate("Phở", listOf(size), listOf(requireNotNull(size.items.single().id), foreignId)) }.errorCode,
        )
        val dup = requireNotNull(size.items.single().id)
        assertEquals(ErrorCode.INVALID_OPTION_SELECTION, errorOf { OptionSelectionValidator.validate("Phở", listOf(size), listOf(dup, dup)) }.errorCode)

        // Nhóm bắt buộc nhưng không còn lựa chọn active bị ẩn khỏi danh sách nhóm hiệu lực -> không bắt buộc, chọn nó bị từ chối.
        assertEquals(0, OptionSelectionValidator.validate("Phở", emptyList(), emptyList()).size)
        assertEquals(ErrorCode.INVALID_OPTION_SELECTION, errorOf { OptionSelectionValidator.validate("Phở", emptyList(), listOf(foreignId)) }.errorCode)
    }

    @Test
    fun `snapshot data keeps group and item pairing`() {
        val size = group("Kích cỡ", SelectionType.SINGLE, 1, 1, "Nhỏ", "Vừa")
        val picked = OptionSelectionValidator.validate("Phở", listOf(size), listOf(requireNotNull(size.items[1].id))).single()
        assertEquals("Kích cỡ", picked.group.name)
        assertEquals("Vừa", picked.item.name)
    }

    // ---------------------------------------------------------------------------- Thất thu
    private val now = OffsetDateTime.parse("2026-11-20T10:00:00+07:00")

    private fun line(status: OrderItemStatus, price: Long, qty: Int = 1): OrderItem {
        val item = OrderItem(1, 1, 1, "Món", price, 0, qty, "device-0001")
        when (status) {
            OrderItemStatus.PENDING -> Unit
            OrderItemStatus.CONFIRMED -> item.changeStatus(OrderItemStatus.CONFIRMED, StatusActor.System, now)
            OrderItemStatus.REJECTED -> item.changeStatus(OrderItemStatus.REJECTED, StatusActor.System, now, "OUT_OF_STOCK")
            else -> error("không dùng trong test")
        }
        return item
    }

    @Test
    fun `billable subtotal counts only CONFIRMED PREPARING READY SERVED (BR-P18)`() {
        val items = listOf(line(OrderItemStatus.CONFIRMED, 65_000, 2), line(OrderItemStatus.PENDING, 99_000), line(OrderItemStatus.REJECTED, 40_000))
        assertEquals(130_000L, ShortfallCalculator.billableSubtotal(items))
        assertTrue(ShortfallCalculator.hasBillableValue(items))
        assertFalse(ShortfallCalculator.hasBillableValue(items.drop(1)), "PENDING và REJECTED không phải món tính tiền")
    }

    @Test
    fun `zero price billable items have no billable value`() {
        assertFalse(ShortfallCalculator.hasBillableValue(listOf(line(OrderItemStatus.CONFIRMED, 0))))
    }

    @Test
    fun `total adds the service fee with HALF_UP rounding and never adds VAT (BR-P01, BR-P02)`() {
        assertEquals(65_000L, ShortfallCalculator.total(65_000, BigDecimal("0.0000")))
        assertEquals(71_500L, ShortfallCalculator.total(65_000, BigDecimal("0.1000")))
        assertEquals(10_001L, ShortfallCalculator.total(10_000, BigDecimal("0.0001")), "10.000 x 0,01% = 1")
        assertEquals(1_001L, ShortfallCalculator.total(1_000, BigDecimal("0.0005")), "0,5 làm tròn lên thành 1")
    }
}
