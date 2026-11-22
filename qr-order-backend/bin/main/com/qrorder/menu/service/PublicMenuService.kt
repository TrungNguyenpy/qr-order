package com.qrorder.menu.service

import com.qrorder.menu.domain.OptionGroup
import com.qrorder.menu.domain.OptionItem
import com.qrorder.menu.domain.ProductStatus
import com.qrorder.menu.dto.PublicCategoryResponse
import com.qrorder.menu.dto.PublicMenuResponse
import com.qrorder.menu.dto.PublicOptionGroupResponse
import com.qrorder.menu.dto.PublicOptionItemResponse
import com.qrorder.menu.dto.PublicProductResponse
import com.qrorder.menu.repository.CategoryRepository
import com.qrorder.menu.repository.OptionGroupRepository
import com.qrorder.menu.repository.OptionItemRepository
import com.qrorder.menu.repository.ProductOptionGroupRepository
import com.qrorder.menu.repository.ProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Thực đơn công khai cho Customer Web (SRS §3.2, FR-PRD-04, NFR-01).
 *
 * Quy tắc hiển thị (module order ở GĐ4 phải kiểm tra lại cùng quy tắc khi nhận order, BR-O02, BR-O03):
 * - Danh mục `visible = true`; món không HIDDEN (SOLD_OUT vẫn hiển thị, `available = false`).
 * - Nhóm option: đã gán cho món, `active = true` và còn ít nhất một lựa chọn `active = true`.
 * - Lựa chọn: `active = true`.
 * - Sắp xếp theo displayOrder rồi id ở mọi cấp; nhóm option theo product_option_group.display_order (displayOrder
 *   của nhóm trong phản hồi là thứ tự trong món đó).
 *
 * Tải bằng 5 truy vấn cố định (không N+1) bất kể số món.
 */
@Service
class PublicMenuService(
    private val categoryRepository: CategoryRepository,
    private val productRepository: ProductRepository,
    private val productOptionGroupRepository: ProductOptionGroupRepository,
    private val optionGroupRepository: OptionGroupRepository,
    private val optionItemRepository: OptionItemRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun getMenu(): PublicMenuResponse {
        val categories = categoryRepository.findAllByVisibleTrueOrderByDisplayOrderAscIdAsc()
        val categoryIds = categories.map { requireNotNull(it.id) }
        val products = if (categoryIds.isEmpty()) emptyList()
        else productRepository.findAllByCategoryIdInAndStatusNotOrderByDisplayOrderAscIdAsc(categoryIds, ProductStatus.HIDDEN)
        val productIds = products.map { requireNotNull(it.id) }

        val links = if (productIds.isEmpty()) emptyList() else productOptionGroupRepository.findAllByIdProductIdIn(productIds)
        val groupIds = links.map { it.id.optionGroupId }.distinct()
        val groups: Map<Long, OptionGroup> = if (groupIds.isEmpty()) emptyMap()
        else optionGroupRepository.findAllById(groupIds).filter { it.active }.associateBy { requireNotNull(it.id) }
        val itemsByGroup: Map<Long, List<OptionItem>> = if (groups.isEmpty()) emptyMap()
        else optionItemRepository.findAllByOptionGroupIdInOrderByDisplayOrderAscIdAsc(groups.keys)
            .filter { it.active }
            .groupBy { it.optionGroupId }

        val publicGroups: Map<Long, PublicOptionGroupResponse> = groups.values
            .mapNotNull { g ->
                val items = itemsByGroup[g.id].orEmpty()
                if (items.isEmpty()) null else requireNotNull(g.id) to toPublic(g, items)
            }
            .toMap()
        val groupsByProduct: Map<Long, List<PublicOptionGroupResponse>> = links
            .groupBy { it.id.productId }
            .mapValues { (_, productLinks) ->
                productLinks.sortedWith(compareBy({ it.displayOrder }, { it.id.optionGroupId }))
                    .mapNotNull { link -> publicGroups[link.id.optionGroupId]?.copy(displayOrder = link.displayOrder) }
            }

        val productsByCategory = products.groupBy { it.categoryId }
        return PublicMenuResponse(
            categories = categories.map { c ->
                PublicCategoryResponse(
                    id = requireNotNull(c.id),
                    name = c.name,
                    description = c.description,
                    displayOrder = c.displayOrder,
                    products = productsByCategory[c.id].orEmpty().map { p ->
                        PublicProductResponse(
                            id = requireNotNull(p.id),
                            name = p.name,
                            description = p.description,
                            price = p.price,
                            imageUrl = p.imageUrl,
                            status = p.status,
                            available = p.status == ProductStatus.AVAILABLE,
                            displayOrder = p.displayOrder,
                            optionGroups = groupsByProduct[p.id].orEmpty(),
                        )
                    },
                )
            },
            generatedAt = OffsetDateTime.now(clock),
        )
    }

    private fun toPublic(g: OptionGroup, items: List<OptionItem>) = PublicOptionGroupResponse(
        id = requireNotNull(g.id),
        name = g.name,
        selectionType = g.selectionType,
        required = g.required,
        minSelect = g.minSelect.toInt(),
        maxSelect = g.maxSelect?.toInt(),
        displayOrder = g.displayOrder,
        items = items.map { PublicOptionItemResponse(requireNotNull(it.id), it.name, it.extraPrice, it.displayOrder) },
    )
}
