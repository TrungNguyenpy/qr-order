package com.qrorder.menu.service

import com.qrorder.common.api.ErrorDetail
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.menu.domain.SelectionType

/** Danh mục audit action của module menu (UPPER_SNAKE_CASE, CHECK ck_audit_log_action). Không bắt buộc lý do. */
object MenuAuditActions {
    const val CATEGORY_CREATED = "CATEGORY_CREATED"
    const val CATEGORY_UPDATED = "CATEGORY_UPDATED"
    const val CATEGORY_VISIBILITY_CHANGED = "CATEGORY_VISIBILITY_CHANGED"
    const val CATEGORIES_REORDERED = "CATEGORIES_REORDERED"
    const val PRODUCT_CREATED = "PRODUCT_CREATED"
    const val PRODUCT_UPDATED = "PRODUCT_UPDATED"
    const val PRODUCT_PRICE_CHANGED = "PRODUCT_PRICE_CHANGED"
    const val PRODUCT_STATUS_CHANGED = "PRODUCT_STATUS_CHANGED"
    const val PRODUCT_IMAGE_CHANGED = "PRODUCT_IMAGE_CHANGED"
    const val PRODUCT_OPTION_GROUPS_CHANGED = "PRODUCT_OPTION_GROUPS_CHANGED"
    const val OPTION_GROUP_CREATED = "OPTION_GROUP_CREATED"
    const val OPTION_GROUP_UPDATED = "OPTION_GROUP_UPDATED"
    const val OPTION_GROUP_ACTIVE_CHANGED = "OPTION_GROUP_ACTIVE_CHANGED"
    const val OPTION_ITEM_CREATED = "OPTION_ITEM_CREATED"
    const val OPTION_ITEM_UPDATED = "OPTION_ITEM_UPDATED"
    const val OPTION_ITEM_ACTIVE_CHANGED = "OPTION_ITEM_ACTIVE_CHANGED"
}

/** audit_log.entity_type = tên bảng (CHECK ck_audit_log_entity_type). */
object MenuAuditEntityTypes {
    const val CATEGORY = "category"
    const val PRODUCT = "product"
    const val OPTION_GROUP = "option_group"
    const val OPTION_ITEM = "option_item"
}

/** Tiện ích chung của các service menu. */
internal object MenuGuards {
    /** Optimistic lock theo version client gửi (NFR-05); @Version của entity là hàng rào cuối khi ghi đồng thời. */
    fun requireVersion(requested: Long?, current: Long) {
        if (requested != current) throw BusinessException(ErrorCode.CONCURRENT_MODIFICATION)
    }

    /** Chuỗi tùy chọn: trim, rỗng -> null. */
    fun optionalText(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** Map các trường đã đổi (giữ thứ tự); dùng cho old_value/new_value của audit. */
    fun changedKeys(before: Map<String, Any?>, after: Map<String, Any?>): List<String> =
        before.keys.filter { before[it] != after[it] }
}

/** Cấu hình nhóm option sau khi điền mặc định. */
data class OptionGroupSpec(
    val selectionType: SelectionType,
    val required: Boolean,
    val minSelect: Int,
    val maxSelect: Int?,
)

/**
 * Ràng buộc nhóm option (FR-PRD-03, BR-O03), trùng với CHECK của option_group nhưng trả lỗi rõ ràng theo trường:
 * - ck_option_group_required: required <=> minSelect >= 1
 * - ck_option_group_single: SINGLE => maxSelect = 1
 * - ck_option_group_max_select: maxSelect null hoặc (>= 1 và >= minSelect)
 * - ck_option_group_min_select: minSelect >= 0
 * Thuần, không phụ thuộc Spring để unit test.
 */
object OptionGroupRules {
    fun resolve(selectionType: SelectionType, required: Boolean, minSelect: Int?, maxSelect: Int?): OptionGroupSpec {
        val min = minSelect ?: if (required) 1 else 0
        val max = maxSelect ?: if (selectionType == SelectionType.SINGLE) 1 else null
        val spec = OptionGroupSpec(selectionType, required, min, max)
        val violations = validate(spec)
        if (violations.isNotEmpty()) throw BusinessException(ErrorCode.VALIDATION_FAILED, details = violations)
        return spec
    }

    fun validate(spec: OptionGroupSpec): List<ErrorDetail> = buildList {
        if (spec.minSelect < 0) add(ErrorDetail("minSelect", "minSelect phải >= 0"))
        if (spec.required && spec.minSelect < 1) add(ErrorDetail("minSelect", "Nhóm bắt buộc phải có minSelect >= 1"))
        if (!spec.required && spec.minSelect >= 1) add(ErrorDetail("minSelect", "Nhóm không bắt buộc phải có minSelect = 0"))
        val max = spec.maxSelect
        if (spec.selectionType == SelectionType.SINGLE && max != 1) {
            add(ErrorDetail("maxSelect", "Nhóm chọn một (SINGLE) phải có maxSelect = 1"))
        }
        if (max != null) {
            if (max < 1) add(ErrorDetail("maxSelect", "maxSelect phải >= 1 hoặc để trống (không giới hạn)"))
            if (max < spec.minSelect) add(ErrorDetail("maxSelect", "maxSelect phải >= minSelect"))
        }
    }
}
