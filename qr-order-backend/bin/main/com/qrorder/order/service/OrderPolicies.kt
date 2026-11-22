package com.qrorder.order.service

import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.settings.OrderConfirmationMode
import com.qrorder.menu.domain.OptionGroup
import com.qrorder.menu.domain.OptionItem
import com.qrorder.menu.domain.SelectionType
import com.qrorder.order.domain.OrderItem
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Quy tắc duyệt đợt theo `restaurant_settings.order_confirmation_mode` (DEC-07 A, BR-S04).
 *
 * Tên giá trị trong database/API cài đặt (không đổi vì không có migration V4):
 * - `ALL`   (cách gọi "EVERY_BATCH")  : mọi đợt PENDING, Staff xác nhận từng đợt.
 * - `NONE`  (cách gọi "AUTO_CONFIRM") : mọi đợt tự CONFIRMED ngay khi gửi.
 * - `FIRST_BATCH_ONLY` (mặc định)    : chỉ đợt đầu cần xác nhận; phiên đã được xác minh (`table_session.verified_at`,
 *   đặt khi Staff xác nhận đợt đầu tiên) thì các đợt sau của cùng Order tự CONFIRMED. Phiên chưa xác minh (đợt đầu
 *   còn PENDING hoặc đã bị từ chối) thì đợt mới vẫn PENDING - chống đặt món giả từ xa (BR-S04).
 */
object OrderConfirmationPolicy {
    fun requiresConfirmation(mode: OrderConfirmationMode, sessionVerified: Boolean): Boolean = when (mode) {
        OrderConfirmationMode.ALL -> true
        OrderConfirmationMode.NONE -> false
        OrderConfirmationMode.FIRST_BATCH_ONLY -> !sessionVerified
    }
}

/** Nhóm option HIỆU LỰC của một món: đã gán, active và còn ít nhất một lựa chọn active (BE-18, giống PublicMenuService). */
data class EffectiveOptionGroup(val group: OptionGroup, val items: List<OptionItem>)

/** Một lựa chọn option đã được kiểm tra, kèm nhóm (để snapshot - BR-O01). */
data class ValidatedOption(val group: OptionGroup, val item: OptionItem)

/**
 * Kiểm tra lựa chọn option theo BR-O02, BR-O03 (hàm thuần, dễ test):
 * - Mỗi optionItemId phải thuộc một nhóm hiệu lực của món (nhóm bị ẩn/lựa chọn ngừng dùng -> không hợp lệ).
 * - Không trùng lựa chọn.
 * - Mỗi nhóm hiệu lực: số lựa chọn >= minSelect (nhóm bắt buộc có minSelect >= 1) và <= maxSelect (SINGLE: max 1).
 * Nhóm KHÔNG hiệu lực (ẩn vì không còn lựa chọn active) không bao giờ bị coi là bắt buộc.
 */
object OptionSelectionValidator {
    fun validate(productName: String, groups: List<EffectiveOptionGroup>, selectedIds: List<Long>): List<ValidatedOption> {
        if (selectedIds.size != selectedIds.toSet().size) invalid("Món \"$productName\": lựa chọn bị trùng")
        val byItemId = groups.flatMap { g -> g.items.map { requireNotNull(it.id) to ValidatedOption(g.group, it) } }.toMap()
        val chosen = selectedIds.map { id ->
            byItemId[id] ?: invalid("Món \"$productName\": lựa chọn #$id không thuộc món hoặc đã ngừng dùng")
        }
        val countByGroup = chosen.groupingBy { it.group.id }.eachCount()
        groups.forEach { eg ->
            val g = eg.group
            val count = countByGroup[g.id] ?: 0
            val max = if (g.selectionType == SelectionType.SINGLE) 1 else g.maxSelect?.toInt()
            if (count < g.minSelect.toInt()) {
                invalid("Món \"$productName\": nhóm \"${g.name}\" cần chọn tối thiểu ${g.minSelect}")
            }
            if (max != null && count > max) invalid("Món \"$productName\": nhóm \"${g.name}\" chỉ được chọn tối đa $max")
        }
        return chosen
    }

    private fun invalid(message: String): Nothing = throw BusinessException(ErrorCode.INVALID_OPTION_SELECTION, message)
}

/**
 * Thất thu khi force-close mà chưa có Bill (BR-P15, BR-P18): Total tính theo BR-P02 từ các món tính tiền, giảm giá 0:
 * Subtotal = tổng line_total món CONFIRMED/PREPARING/READY/SERVED; phí phục vụ = HALF_UP(Subtotal x tỷ lệ) (BR-P01);
 * Total = Subtotal + phí. Giá menu đã gồm VAT nên không cộng VAT. Chưa có Payment nên net_collected = 0.
 * GĐ5-GĐ6: khi có Bill ACTIVE, Total lấy từ Bill và trừ net_collected.
 */
object ShortfallCalculator {
    /** Order có giá trị cần tính tiền khi có ít nhất một món tính tiền với thành tiền > 0 (BR-P18). */
    fun billableSubtotal(items: List<OrderItem>): Long = items.filter { it.status.isBillable }.sumOf { it.lineTotal }

    fun hasBillableValue(items: List<OrderItem>): Boolean = items.any { it.status.isBillable && it.lineTotal > 0 }

    fun total(subtotal: Long, serviceFeeRate: BigDecimal): Long {
        val fee = BigDecimal(subtotal).multiply(serviceFeeRate).setScale(0, RoundingMode.HALF_UP).toLong()
        return subtotal + fee
    }
}
