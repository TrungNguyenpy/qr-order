package com.qrorder.menu.repository

import com.qrorder.menu.domain.Category
import com.qrorder.menu.domain.OptionGroup
import com.qrorder.menu.domain.OptionItem
import com.qrorder.menu.domain.Product
import com.qrorder.menu.domain.ProductOptionGroup
import com.qrorder.menu.domain.ProductOptionGroupId
import com.qrorder.menu.domain.ProductStatus
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CategoryRepository : JpaRepository<Category, Long>, JpaSpecificationExecutor<Category> {
    /** Trùng tên không phân biệt hoa thường (ux_category_name_lower). [excludeId] = -1 khi tạo mới. */
    fun existsByNameIgnoreCaseAndIdNot(name: String, excludeId: Long): Boolean

    fun findAllByVisibleTrueOrderByDisplayOrderAscIdAsc(): List<Category>
}

interface ProductRepository : JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    /** Public menu: món không HIDDEN của các danh mục hiển thị (index ix_product_menu). */
    fun findAllByCategoryIdInAndStatusNotOrderByDisplayOrderAscIdAsc(
        categoryIds: Collection<Long>,
        status: ProductStatus,
    ): List<Product>

    /**
     * Tăng version của món khi đổi cấu hình liên quan nhưng không đổi cột nào của bảng product (gán/gỡ nhóm option):
     * client đang giữ version cũ sẽ nhận 409. 0 dòng = version đã bị người khác đổi.
     * flushAutomatically: thay đổi đang chờ (product_option_group) được ghi trước câu lệnh này.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "UPDATE product SET version = version + 1, updated_at = now() WHERE id = :id AND version = :version",
        nativeQuery = true,
    )
    fun bumpVersion(@Param("id") id: Long, @Param("version") version: Long): Int
}

interface OptionGroupRepository : JpaRepository<OptionGroup, Long>, JpaSpecificationExecutor<OptionGroup>

interface OptionItemRepository : JpaRepository<OptionItem, Long> {
    fun findAllByOptionGroupIdInOrderByDisplayOrderAscIdAsc(groupIds: Collection<Long>): List<OptionItem>

    fun findByIdAndOptionGroupId(id: Long, optionGroupId: Long): OptionItem?

    /** Trùng tên trong nhóm giữa các lựa chọn đang dùng (ux_option_item_group_name_active). [excludeId] = -1 khi tạo mới. */
    fun existsByOptionGroupIdAndActiveTrueAndNameIgnoreCaseAndIdNot(optionGroupId: Long, name: String, excludeId: Long): Boolean
}

interface ProductOptionGroupRepository : JpaRepository<ProductOptionGroup, ProductOptionGroupId> {
    fun findAllByIdProductIdOrderByDisplayOrderAsc(productId: Long): List<ProductOptionGroup>

    fun findAllByIdProductIdIn(productIds: Collection<Long>): List<ProductOptionGroup>
}

/** Bộ lọc tìm kiếm danh sách quản trị (FR-CAT-01, FR-PRD-04). Từ khóa so khớp chứa, không phân biệt hoa thường. */
object MenuSpecifications {
    private const val LIKE_ESCAPE = '\\'

    fun categories(keyword: String?, visible: Boolean?): Specification<Category> =
        Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            if (visible != null) predicates += cb.equal(root.get<Boolean>("visible"), visible)
            containsPattern(keyword)?.let { predicates += cb.like(cb.lower(root.get("name")), it, LIKE_ESCAPE) }
            cb.and(*predicates.toTypedArray())
        }

    fun products(keyword: String?, categoryId: Long?, status: ProductStatus?): Specification<Product> =
        Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            if (categoryId != null) predicates += cb.equal(root.get<Long>("categoryId"), categoryId)
            if (status != null) predicates += cb.equal(root.get<ProductStatus>("status"), status)
            containsPattern(keyword)?.let { predicates += cb.like(cb.lower(root.get("name")), it, LIKE_ESCAPE) }
            cb.and(*predicates.toTypedArray())
        }

    fun optionGroups(keyword: String?, active: Boolean?): Specification<OptionGroup> =
        Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            if (active != null) predicates += cb.equal(root.get<Boolean>("active"), active)
            containsPattern(keyword)?.let { predicates += cb.like(cb.lower(root.get("name")), it, LIKE_ESCAPE) }
            cb.and(*predicates.toTypedArray())
        }

    /** `%từ khóa%` chữ thường, đã thoát ký tự đại diện của LIKE; null nếu từ khóa trống. */
    fun containsPattern(keyword: String?): String? =
        keyword?.trim()?.takeIf { it.isNotEmpty() }?.let { "%" + escapeLike(it.lowercase()) + "%" }

    fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
