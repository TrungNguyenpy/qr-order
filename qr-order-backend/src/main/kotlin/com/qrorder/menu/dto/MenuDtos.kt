package com.qrorder.menu.dto

import com.qrorder.menu.domain.Category
import com.qrorder.menu.domain.OptionGroup
import com.qrorder.menu.domain.OptionItem
import com.qrorder.menu.domain.Product
import com.qrorder.menu.domain.ProductStatus
import com.qrorder.menu.domain.SelectionType
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

/*
 * Hợp đồng API Giai đoạn 2 - Menu (Admin Dashboard, Staff App, Customer Web).
 * Quy ước chung:
 * - Tiền là số nguyên VND (Long), đã gồm VAT (DEC-10 A).
 * - Mọi request sửa phải gửi kèm `version` đã đọc; lệch version -> 409 CONCURRENT_MODIFICATION (NFR-05).
 * - Tên trường theo entity/schema: danh mục ẩn/hiện = `visible` (cột is_visible); nhóm/lựa chọn ngừng dùng =
 *   `active` (cột is_active); phụ thu lựa chọn = `extraPrice` (cột extra_price).
 */

private const val NAME_MAX_100 = "Tên tối đa 100 ký tự"
private const val DESCRIPTION_MAX = 2000
private const val MAX_DISPLAY_ORDER = 1_000_000L

/** Giá tối đa chấp nhận ở API (1.000 tỷ VND) - giới hạn hợp lý cho BIGINT, chặn nhập nhầm. */
const val MAX_MONEY_VND = 1_000_000_000_000L

// =============================================================================================== Category

data class CategoryResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val displayOrder: Int,
    val visible: Boolean,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?,
    val version: Long,
) {
    companion object {
        fun of(c: Category) = CategoryResponse(
            id = requireNotNull(c.id),
            name = c.name,
            description = c.description,
            displayOrder = c.displayOrder,
            visible = c.visible,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
            version = c.version,
        )
    }
}

data class CreateCategoryRequest(
    @field:NotBlank(message = "Tên danh mục là bắt buộc")
    @field:Size(max = 100, message = NAME_MAX_100)
    val name: String? = null,

    @field:Size(max = DESCRIPTION_MAX, message = "Mô tả tối đa 2000 ký tự")
    val description: String? = null,

    @field:PositiveOrZero(message = "Thứ tự hiển thị phải >= 0")
    @field:Max(value = MAX_DISPLAY_ORDER, message = "Thứ tự hiển thị quá lớn")
    val displayOrder: Int? = null,

    /** Mặc định true (hiển thị). */
    val visible: Boolean? = null,
)

/** Sửa thông tin danh mục; ẩn/hiện dùng endpoint riêng PATCH /visibility. */
data class UpdateCategoryRequest(
    @field:NotBlank(message = "Tên danh mục là bắt buộc")
    @field:Size(max = 100, message = NAME_MAX_100)
    val name: String? = null,

    @field:Size(max = DESCRIPTION_MAX, message = "Mô tả tối đa 2000 ký tự")
    val description: String? = null,

    @field:NotNull(message = "Thứ tự hiển thị là bắt buộc")
    @field:PositiveOrZero(message = "Thứ tự hiển thị phải >= 0")
    @field:Max(value = MAX_DISPLAY_ORDER, message = "Thứ tự hiển thị quá lớn")
    val displayOrder: Int? = null,

    @field:NotNull(message = "version là bắt buộc")
    val version: Long? = null,
)

data class ChangeCategoryVisibilityRequest(
    @field:NotNull(message = "visible là bắt buộc")
    val visible: Boolean? = null,

    @field:NotNull(message = "version là bắt buộc")
    val version: Long? = null,
)

data class CategoryOrderItem(
    @field:NotNull(message = "id là bắt buộc")
    val id: Long? = null,

    @field:NotNull(message = "Thứ tự hiển thị là bắt buộc")
    @field:PositiveOrZero(message = "Thứ tự hiển thị phải >= 0")
    @field:Max(value = MAX_DISPLAY_ORDER, message = "Thứ tự hiển thị quá lớn")
    val displayOrder: Int? = null,

    @field:NotNull(message = "version là bắt buộc")
    val version: Long? = null,
)

/** Sắp xếp lại nhiều danh mục trong một lần (kéo-thả trên Admin Dashboard). */
data class ReorderCategoriesRequest(
    @field:NotEmpty(message = "Danh sách sắp xếp không được rỗng")
    @field:Size(max = 500, message = "Tối đa 500 danh mục mỗi lần")
    @field:Valid
    val items: List<CategoryOrderItem>? = null,
)

// =============================================================================================== Product

/** Nhóm option đã gán cho món (bảng product_option_group), theo thứ tự hiển thị. */
data class AssignedOptionGroupResponse(
    val optionGroupId: Long,
    val name: String,
    val selectionType: SelectionType,
    val required: Boolean,
    val active: Boolean,
    val displayOrder: Int,
)

/**
 * Món cho Admin/Staff. [optionGroups]: có ở API một món (chi tiết, tạo, sửa...); null ở danh sách phân trang.
 * Không trả image_public_id (chi tiết lưu trữ nội bộ).
 */
data class ProductResponse(
    val id: Long,
    val categoryId: Long,
    val name: String,
    val description: String?,
    val price: Long,
    val imageUrl: String?,
    val status: ProductStatus,
    val displayOrder: Int,
    val statusChangedAt: OffsetDateTime?,
    val statusChangedBy: Long?,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?,
    val version: Long,
    val optionGroups: List<AssignedOptionGroupResponse>? = null,
) {
    companion object {
        fun of(p: Product, optionGroups: List<AssignedOptionGroupResponse>? = null) = ProductResponse(
            id = requireNotNull(p.id),
            categoryId = p.categoryId,
            name = p.name,
            description = p.description,
            price = p.price,
            imageUrl = p.imageUrl,
            status = p.status,
            displayOrder = p.displayOrder,
            statusChangedAt = p.statusChangedAt,
            statusChangedBy = p.statusChangedBy,
            createdAt = p.createdAt,
            updatedAt = p.updatedAt,
            version = p.version,
            optionGroups = optionGroups,
        )
    }
}

data class CreateProductRequest(
    @field:NotNull(message = "Danh mục là bắt buộc")
    val categoryId: Long? = null,

    @field:NotBlank(message = "Tên món là bắt buộc")
    @field:Size(max = 150, message = "Tên món tối đa 150 ký tự")
    val name: String? = null,

    @field:Size(max = DESCRIPTION_MAX, message = "Mô tả tối đa 2000 ký tự")
    val description: String? = null,

    /** Giá VND đã gồm VAT, >= 0 (món miễn phí được phép, CHECK ck_product_price). */
    @field:NotNull(message = "Giá là bắt buộc")
    @field:PositiveOrZero(message = "Giá phải >= 0")
    @field:Max(value = MAX_MONEY_VND, message = "Giá quá lớn")
    val price: Long? = null,

    @field:PositiveOrZero(message = "Thứ tự hiển thị phải >= 0")
    @field:Max(value = MAX_DISPLAY_ORDER, message = "Thứ tự hiển thị quá lớn")
    val displayOrder: Int? = null,

    /** Mặc định AVAILABLE. */
    val status: ProductStatus? = null,
)

/** Sửa thông tin và giá (đổi giá ghi audit PRODUCT_PRICE_CHANGED riêng). Trạng thái dùng PATCH /status. */
data class UpdateProductRequest(
    @field:NotNull(message = "Danh mục là bắt buộc")
    val categoryId: Long? = null,

    @field:NotBlank(message = "Tên món là bắt buộc")
    @field:Size(max = 150, message = "Tên món tối đa 150 ký tự")
    val name: String? = null,

    @field:Size(max = DESCRIPTION_MAX, message = "Mô tả tối đa 2000 ký tự")
    val description: String? = null,

    @field:NotNull(message = "Giá là bắt buộc")
    @field:PositiveOrZero(message = "Giá phải >= 0")
    @field:Max(value = MAX_MONEY_VND, message = "Giá quá lớn")
    val price: Long? = null,

    @field:NotNull(message = "Thứ tự hiển thị là bắt buộc")
    @field:PositiveOrZero(message = "Thứ tự hiển thị phải >= 0")
    @field:Max(value = MAX_DISPLAY_ORDER, message = "Thứ tự hiển thị quá lớn")
    val displayOrder: Int? = null,

    @field:NotNull(message = "version là bắt buộc")
    val version: Long? = null,
)

/**
 * FR-PRD-02: đổi trạng thái thủ công. AVAILABLE <-> SOLD_OUT: Bếp/Staff/Manager/Admin.
 * Liên quan HIDDEN (ẩn hoặc bỏ ẩn): chỉ ADMIN/MANAGER.
 */
data class ChangeProductStatusRequest(
    @field:NotNull(message = "Trạng thái là bắt buộc")
    val status: ProductStatus? = null,

    @field:NotNull(message = "version là bắt buộc")
    val version: Long? = null,

    @field:Size(max = 500, message = "Lý do tối đa 500 ký tự")
    val reason: String? = null,
)

/**
 * Gán/gỡ/sắp xếp nhóm option của món trong một lần: danh sách là toàn bộ nhóm sau thay đổi, theo thứ tự
 * hiển thị (phần tử đầu displayOrder = 1). Danh sách rỗng = gỡ hết. [version] là version của món.
 */
data class UpdateProductOptionGroupsRequest(
    @field:NotNull(message = "optionGroupIds là bắt buộc (rỗng để gỡ hết)")
    @field:Size(max = 50, message = "Tối đa 50 nhóm option mỗi món")
    val optionGroupIds: List<Long>? = null,

    @field:NotNull(message = "version là bắt buộc")
    val version: Long? = null,
)

// =============================================================================================== Option group / item

data class OptionItemResponse(
    val id: Long,
    val optionGroupId: Long,
    val name: String,
    val extraPrice: Long,
    val active: Boolean,
    val displayOrder: Int,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?,
    val version: Long,
) {
    companion object {
        fun of(i: OptionItem) = OptionItemResponse(
            id = requireNotNull(i.id),
            optionGroupId = i.optionGroupId,
            name = i.name,
            extraPrice = i.extraPrice,
            active = i.active,
            displayOrder = i.displayOrder,
            createdAt = i.createdAt,
            updatedAt = i.updatedAt,
            version = i.version,
        )
    }
}

/** Nhóm option kèm toàn bộ lựa chọn (gồm cả lựa chọn đã ngừng dùng, theo displayOrder). */
data class OptionGroupResponse(
    val id: Long,
    val name: String,
    val selectionType: SelectionType,
    val required: Boolean,
    val minSelect: Int,
    val maxSelect: Int?,
    val active: Boolean,
    val displayOrder: Int,
    val items: List<OptionItemResponse>,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?,
    val version: Long,
) {
    companion object {
        fun of(g: OptionGroup, items: List<OptionItem>) = OptionGroupResponse(
            id = requireNotNull(g.id),
            name = g.name,
            selectionType = g.selectionType,
            required = g.required,
            minSelect = g.minSelect.toInt(),
            maxSelect = g.maxSelect?.toInt(),
            active = g.active,
            displayOrder = g.displayOrder,
            items = items.map { OptionItemResponse.of(it) },
            createdAt = g.createdAt,
            updatedAt = g.updatedAt,
            version = g.version,
        )
    }
}

/**
 * FR-PRD-03 / BR-O03. Bỏ trống [minSelect] thì lấy theo [required] (1 nếu bắt buộc, 0 nếu không);
 * bỏ trống [maxSelect] thì SINGLE = 1, MULTIPLE = không giới hạn. Ràng buộc đầy đủ: OptionGroupRules.
 */
data class CreateOptionGroupRequest(
    @field:NotBlank(message = "Tên nhóm là bắt buộc")
    @field:Size(max = 100, message = NAME_MAX_100)
    val name: String? = null,

    @field:NotNull(message = "Kiểu chọn (SINGLE/MULTIPLE) là bắt buộc")
    val selectionType: SelectionType? = null,

    @field:NotNull(message = "required là bắt buộc")
    val required: Boolean? = null,

    @field:PositiveOrZero(message = "minSelect phải >= 0")
    @field:Max(value = 100, message = "minSelect tối đa 100")
    val minSelect: Int? = null,

    @field:Max(value = 100, message = "maxSelect tối đa 100")
    val maxSelect: Int? = null,

    @field:PositiveOrZero(message = "Thứ tự hiển thị phải >= 0")
    @field:Max(value = MAX_DISPLAY_ORDER, message = "Thứ tự hiển thị quá lớn")
    val displayOrder: Int? = null,
)

data class UpdateOptionGroupRequest(
    @field:NotBlank(message = "Tên nhóm là bắt buộc")
    @field:Size(max = 100, message = NAME_MAX_100)
    val name: String? = null,

    @field:NotNull(message = "Kiểu chọn (SINGLE/MULTIPLE) là bắt buộc")
    val selectionType: SelectionType? = null,

    @field:NotNull(message = "required là bắt buộc")
    val required: Boolean? = null,

    @field:PositiveOrZero(message = "minSelect phải >= 0")
    @field:Max(value = 100, message = "minSelect tối đa 100")
    val minSelect: Int? = null,

    @field:Max(value = 100, message = "maxSelect tối đa 100")
    val maxSelect: Int? = null,

    @field:NotNull(message = "Thứ tự hiển thị là bắt buộc")
    @field:PositiveOrZero(message = "Thứ tự hiển thị phải >= 0")
    @field:Max(value = MAX_DISPLAY_ORDER, message = "Thứ tự hiển thị quá lớn")
    val displayOrder: Int? = null,

    @field:NotNull(message = "version là bắt buộc")
    val version: Long? = null,
)

/** Ngừng dùng / dùng lại (thay cho xóa, BR-A03) - nhóm option và lựa chọn. */
data class ChangeActiveRequest(
    @field:NotNull(message = "active là bắt buộc")
    val active: Boolean? = null,

    @field:NotNull(message = "version là bắt buộc")
    val version: Long? = null,
)

data class CreateOptionItemRequest(
    @field:NotBlank(message = "Tên lựa chọn là bắt buộc")
    @field:Size(max = 100, message = NAME_MAX_100)
    val name: String? = null,

    /** Phụ thu VND đã gồm VAT, >= 0 (CHECK ck_option_item_extra_price). Mặc định 0. */
    @field:PositiveOrZero(message = "Phụ thu phải >= 0")
    @field:Max(value = MAX_MONEY_VND, message = "Phụ thu quá lớn")
    val extraPrice: Long? = null,

    @field:PositiveOrZero(message = "Thứ tự hiển thị phải >= 0")
    @field:Max(value = MAX_DISPLAY_ORDER, message = "Thứ tự hiển thị quá lớn")
    val displayOrder: Int? = null,
)

data class UpdateOptionItemRequest(
    @field:NotBlank(message = "Tên lựa chọn là bắt buộc")
    @field:Size(max = 100, message = NAME_MAX_100)
    val name: String? = null,

    @field:NotNull(message = "Phụ thu là bắt buộc")
    @field:PositiveOrZero(message = "Phụ thu phải >= 0")
    @field:Max(value = MAX_MONEY_VND, message = "Phụ thu quá lớn")
    val extraPrice: Long? = null,

    @field:NotNull(message = "Thứ tự hiển thị là bắt buộc")
    @field:PositiveOrZero(message = "Thứ tự hiển thị phải >= 0")
    @field:Max(value = MAX_DISPLAY_ORDER, message = "Thứ tự hiển thị quá lớn")
    val displayOrder: Int? = null,

    @field:NotNull(message = "version là bắt buộc")
    val version: Long? = null,
)

// =============================================================================================== Public menu

/**
 * GET /api/v1/public/menu (Customer Web, SRS §3.2): chỉ danh mục đang hiển thị, món không HIDDEN
 * (SOLD_OUT vẫn trả về với `available = false` để hiển thị mờ), nhóm option đang dùng có ít nhất một lựa chọn
 * đang dùng, lựa chọn đang dùng; tất cả theo displayOrder rồi id. Không có trường nội bộ (version, người sửa).
 */
data class PublicMenuResponse(
    val categories: List<PublicCategoryResponse>,
    val generatedAt: OffsetDateTime,
)

data class PublicCategoryResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val displayOrder: Int,
    val products: List<PublicProductResponse>,
)

data class PublicProductResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val price: Long,
    val imageUrl: String?,
    val status: ProductStatus,
    val available: Boolean,
    val displayOrder: Int,
    val optionGroups: List<PublicOptionGroupResponse>,
)

data class PublicOptionGroupResponse(
    val id: Long,
    val name: String,
    val selectionType: SelectionType,
    val required: Boolean,
    val minSelect: Int,
    val maxSelect: Int?,
    val displayOrder: Int,
    val items: List<PublicOptionItemResponse>,
)

data class PublicOptionItemResponse(
    val id: Long,
    val name: String,
    val extraPrice: Long,
    val displayOrder: Int,
)
