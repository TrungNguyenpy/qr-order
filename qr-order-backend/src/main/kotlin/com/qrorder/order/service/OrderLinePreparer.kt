package com.qrorder.order.service

import com.qrorder.common.api.ErrorDetail
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.settings.RestaurantSettings
import com.qrorder.menu.domain.OptionItem
import com.qrorder.menu.domain.Product
import com.qrorder.menu.domain.ProductStatus
import com.qrorder.menu.repository.CategoryRepository
import com.qrorder.menu.repository.OptionGroupRepository
import com.qrorder.menu.repository.OptionItemRepository
import com.qrorder.menu.repository.ProductOptionGroupRepository
import com.qrorder.menu.repository.ProductRepository
import com.qrorder.order.domain.OrderItem
import com.qrorder.order.domain.OrderItemOption
import com.qrorder.order.dto.PlaceOrderLine
import com.qrorder.order.repository.OrderItemOptionRepository
import com.qrorder.order.repository.OrderItemRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/** Một dòng món đã được kiểm tra: món, số lượng, ghi chú đã làm sạch và các lựa chọn option hợp lệ (kèm nhóm để snapshot). */
class PreparedLine(val product: Product, val quantity: Int, val note: String?, val options: List<ValidatedOption>)

/**
 * Kiểm tra và lưu các dòng món của một đợt order - DÙNG CHUNG cho khách đặt món ([OrderPlacementService]) và nhân viên
 * đặt món hộ ([StaffOrderService], FR-ORD-05, BE-37). Hai luồng chỉ khác nhau ở người đặt, rate limit, PIN và cách duyệt
 * đợt; quy tắc về món, option, số lượng và snapshot giống hệt nhau (BR-O01, BR-O02, BR-O03, BE-04, BE-18).
 *
 * Hàm không tự mở transaction: caller phải đang giữ khóa bàn + phiên trong transaction của mình.
 */
@Component
class OrderLinePreparer(
    private val itemRepository: OrderItemRepository,
    private val optionRepository: OrderItemOptionRepository,
    private val productRepository: ProductRepository,
    private val categoryRepository: CategoryRepository,
    private val productOptionGroupRepository: ProductOptionGroupRepository,
    private val optionGroupRepository: OptionGroupRepository,
    private val optionItemRepository: OptionItemRepository,
) {
    /** Kiểm tra từng dòng (BR-O02, BR-O03, BE-18); nạp theo lô, không N+1. */
    fun prepare(lines: List<PlaceOrderLine>, settings: RestaurantSettings): List<PreparedLine> {
        val errors = mutableListOf<ErrorDetail>()
        lines.forEachIndexed { index, line ->
            val q = line.quantity
            if (q == null || q < 1 || q > settings.maxItemQuantity) {
                errors += ErrorDetail("items[$index].quantity", "Số lượng phải từ 1 đến ${settings.maxItemQuantity}")
            }
        }
        if (errors.isNotEmpty()) throw BusinessException(ErrorCode.VALIDATION_FAILED, details = errors)

        val productIds = lines.map { requireNotNull(it.productId) }.distinct()
        val products = productRepository.findAllById(productIds).associateBy { requireNotNull(it.id) }
        val visibleCategories = categoryRepository.findAllById(products.values.map { it.categoryId }.distinct())
            .filter { it.visible }.map { requireNotNull(it.id) }.toSet()
        productIds.forEach { id ->
            val p = products[id] ?: throw BusinessException(ErrorCode.INVALID_REFERENCE, "Món #$id không tồn tại")
            if (p.status != ProductStatus.AVAILABLE || p.categoryId !in visibleCategories) {
                throw BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE, "Món \"${p.name}\" hiện không phục vụ")
            }
        }

        val links = productOptionGroupRepository.findAllByIdProductIdIn(productIds)
        val groups = optionGroupRepository.findAllById(links.map { it.id.optionGroupId }.distinct()).filter { it.active }
            .associateBy { requireNotNull(it.id) }
        val itemsByGroup: Map<Long, List<OptionItem>> = if (groups.isEmpty()) emptyMap()
        else optionItemRepository.findAllByOptionGroupIdInOrderByDisplayOrderAscIdAsc(groups.keys).filter { it.active }
            .groupBy { it.optionGroupId }
        val effectiveByProduct = links.groupBy { it.id.productId }.mapValues { (_, productLinks) ->
            productLinks.sortedWith(compareBy({ it.displayOrder }, { it.id.optionGroupId })).mapNotNull { link ->
                val group = groups[link.id.optionGroupId] ?: return@mapNotNull null
                val items = itemsByGroup[group.id].orEmpty()
                if (items.isEmpty()) null else EffectiveOptionGroup(group, items)
            }
        }

        return lines.map { line ->
            val product = products.getValue(requireNotNull(line.productId))
            val options = OptionSelectionValidator.validate(product.name, effectiveByProduct[product.id].orEmpty(), line.optionItemIds)
            PreparedLine(product, requireNotNull(line.quantity), line.note?.trim()?.takeIf { it.isNotEmpty() }, options)
        }
    }

    /**
     * Lưu các món của đợt [batchId] kèm option (snapshot tên/giá/phụ thu - BR-O01, line_total chính xác - BE-04).
     * [confirmedAt] khác null: món tạo ở CONFIRMED ngay (đợt không cần duyệt). Đúng một trong [deviceId] (khách) và
     * [createdByUserId] (nhân viên đặt hộ) được truyền (CHECK ck_order_item_orderer); trigger lịch sử món ghi người đặt
     * theo hai cột này (STAFF nếu có created_by_user_id, ngược lại GUEST).
     */
    fun persist(
        orderId: Long,
        batchId: Long,
        prepared: List<PreparedLine>,
        confirmedAt: OffsetDateTime?,
        deviceId: String?,
        createdByUserId: Long?,
    ): List<OrderItem> {
        require((deviceId == null) != (createdByUserId == null)) { "Món phải có đúng một người đặt: thiết bị khách hoặc nhân viên" }
        val items = prepared.map { p ->
            OrderItem(
                orderId = orderId, batchId = batchId, productId = requireNotNull(p.product.id),
                productName = p.product.name, unitPrice = p.product.price,
                optionExtraPerUnit = p.options.sumOf { it.item.extraPrice }, quantity = p.quantity,
                deviceId = deviceId, createdByUserId = createdByUserId,
            ).apply {
                note = p.note
                if (confirmedAt != null) initiallyConfirmed(confirmedAt)
            }
        }
        val savedItems = itemRepository.saveAllAndFlush(items)
        savedItems.forEachIndexed { index, item ->
            val options = prepared[index].options.map { o ->
                OrderItemOption(requireNotNull(item.id), requireNotNull(o.group.id), requireNotNull(o.item.id), o.group.name, o.item.name, o.item.extraPrice)
            }
            if (options.isNotEmpty()) optionRepository.saveAll(options)
        }
        return savedItems
    }
}
