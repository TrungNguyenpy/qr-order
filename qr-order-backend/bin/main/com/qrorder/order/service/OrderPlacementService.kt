package com.qrorder.order.service

import com.qrorder.auth.security.GuestPrincipal
import com.qrorder.common.api.ErrorDetail
import com.qrorder.common.domain.RequesterType
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.RateLimitExceededException
import com.qrorder.common.settings.RestaurantSettings
import com.qrorder.common.settings.RestaurantSettingsService
import com.qrorder.common.time.DbClock
import com.qrorder.menu.domain.OptionItem
import com.qrorder.menu.domain.Product
import com.qrorder.menu.domain.ProductStatus
import com.qrorder.menu.repository.CategoryRepository
import com.qrorder.menu.repository.OptionGroupRepository
import com.qrorder.menu.repository.OptionItemRepository
import com.qrorder.menu.repository.ProductOptionGroupRepository
import com.qrorder.menu.repository.ProductRepository
import com.qrorder.order.domain.BatchStatus
import com.qrorder.order.domain.Order
import com.qrorder.order.domain.OrderBatch
import com.qrorder.order.domain.OrderItem
import com.qrorder.order.domain.OrderItemOption
import com.qrorder.order.domain.OrderStatus
import com.qrorder.order.dto.GuestOrderResponse
import com.qrorder.order.dto.PlaceOrderLine
import com.qrorder.order.dto.PlaceOrderRequest
import com.qrorder.order.event.OrderChangeKind
import com.qrorder.order.repository.OrderBatchRepository
import com.qrorder.order.repository.OrderItemOptionRepository
import com.qrorder.order.repository.OrderItemRepository
import com.qrorder.order.repository.OrderRepository
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.repository.SessionDeviceRepository
import com.qrorder.table.session.JoinRateLimiter
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * Khách gửi đợt order (FR-ORD-01..04, BR-O01..O05, BE-04, BE-18, BE-23).
 *
 * Thứ tự (BR-S09 trước, rồi trong MỘT transaction giữ khóa bàn + phiên):
 * 1. Rate limit `order:{sessionId}` (order_rate_limit_per_minute) - ngoài transaction, đếm cả yêu cầu sai -> 429.
 * 2. Khóa bàn rồi phiên (cùng thứ tự với force-close nên không deadlock).
 * 3. Idempotent: đã có đợt cùng clientRequestId -> trả lại kết quả cũ (`replayed = true`), không tạo đợt mới.
 * 4. Phiên phải OPEN (BILLING/đã đóng -> 409 SESSION_NOT_OPEN); Order (nếu đã có) phải OPEN (BR-O12).
 * 5. PIN (BE-23): require_table_pin bật, phiên có PIN mà thiết bị chưa xác minh -> 403 TABLE_PIN_REQUIRED.
 * 6. Số đợt PENDING < max_pending_batches_per_session (BR-S09).
 * 7. Mỗi dòng: món AVAILABLE và danh mục hiển thị (BR-O02); số lượng 1..max_item_quantity; option theo BR-O03 và
 *    quy tắc nhóm ẩn của PublicMenuService (BE-18).
 * 8. Snapshot tên/giá/option (BR-O01), line_total chính xác (BE-04), tạo Order (nếu chưa có, DEC-02 A) + đợt + món;
 *    đợt cần xác nhận hay không theo [OrderConfirmationPolicy]; cập nhật last_activity_at của phiên.
 */
@Service
class OrderPlacementService(
    private val locker: SessionOrderLocker,
    private val settingsService: RestaurantSettingsService,
    private val rateLimiter: JoinRateLimiter,
    private val orderRepository: OrderRepository,
    private val batchRepository: OrderBatchRepository,
    private val itemRepository: OrderItemRepository,
    private val optionRepository: OrderItemOptionRepository,
    private val deviceRepository: SessionDeviceRepository,
    private val productRepository: ProductRepository,
    private val categoryRepository: CategoryRepository,
    private val productOptionGroupRepository: ProductOptionGroupRepository,
    private val optionGroupRepository: OptionGroupRepository,
    private val optionItemRepository: OptionItemRepository,
    private val queryService: OrderQueryService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    fun place(principal: GuestPrincipal, request: PlaceOrderRequest): GuestOrderResponse {
        val settings = settingsService.current()
        val decision = rateLimiter.tryAcquire("order:${principal.sessionId}", settings.orderRateLimitPerMinute, RATE_LIMIT_WINDOW)
        if (!decision.allowed) throw RateLimitExceededException(decision.retryAfterSeconds)
        return requireNotNull(tx.execute { placeInTransaction(principal, request, settingsService.current()) })
    }

    private fun placeInTransaction(principal: GuestPrincipal, request: PlaceOrderRequest, settings: RestaurantSettings): GuestOrderResponse {
        val clientRequestId = requireNotNull(request.clientRequestId)
        val lines = requireNotNull(request.items)
        val locked = locker.lock(principal.sessionId)
        val session = locked.session

        batchRepository.findBySessionIdAndClientRequestId(locked.sessionId, clientRequestId)?.let { existing ->
            return view(locked.sessionId, replayed = true, batchId = existing.id)
        }

        if (session.status != SessionStatus.OPEN) throw BusinessException(ErrorCode.SESSION_NOT_OPEN)
        val device = deviceRepository.findBySessionIdAndDeviceId(locked.sessionId, principal.deviceId)
            ?: throw BusinessException(ErrorCode.TOKEN_INVALID)
        if (settings.requireTablePin && session.tablePin != null && device.pinVerifiedAt == null) {
            throw BusinessException(ErrorCode.TABLE_PIN_REQUIRED)
        }
        var order = orderRepository.findBySessionId(locked.sessionId)
        if (order != null && order.status != OrderStatus.OPEN) {
            throw BusinessException(ErrorCode.ORDER_NOT_OPEN, "Order đang ${order.status}, không nhận thêm đợt mới")
        }
        if (batchRepository.countBySessionIdAndStatus(locked.sessionId, BatchStatus.PENDING) >= settings.maxPendingBatchesPerSession) {
            throw BusinessException(ErrorCode.MAX_PENDING_BATCHES_REACHED)
        }

        val prepared = prepareLines(lines, settings)
        val now = dbClock.now()
        if (order == null) order = orderRepository.saveAndFlush(Order(locked.sessionId))
        val orderId = requireNotNull(order.id)

        val needsConfirmation = OrderConfirmationPolicy.requiresConfirmation(settings.orderConfirmationMode, session.verifiedAt != null)
        val batch = OrderBatch(
            orderId = orderId, sessionId = locked.sessionId, seq = batchRepository.maxSeq(orderId) + 1,
            clientRequestId = clientRequestId, requiresConfirmation = needsConfirmation, submittedByType = RequesterType.GUEST,
        ).apply {
            deviceId = principal.deviceId
            if (!needsConfirmation) {
                status = BatchStatus.ACCEPTED
                decidedAt = now
            }
        }
        val savedBatch = batchRepository.saveAndFlush(batch)

        val items = prepared.map { p ->
            OrderItem(
                orderId = orderId, batchId = requireNotNull(savedBatch.id), productId = requireNotNull(p.product.id),
                productName = p.product.name, unitPrice = p.product.price,
                optionExtraPerUnit = p.options.sumOf { it.item.extraPrice }, quantity = p.quantity, deviceId = principal.deviceId,
            ).apply {
                note = p.note
                if (!needsConfirmation) initiallyConfirmed(now)
            }
        }
        val savedItems = itemRepository.saveAllAndFlush(items)
        savedItems.forEachIndexed { index, item ->
            val options = prepared[index].options.map { o ->
                OrderItemOption(requireNotNull(item.id), requireNotNull(o.group.id), requireNotNull(o.item.id), o.group.name, o.item.name, o.item.extraPrice)
            }
            if (options.isNotEmpty()) optionRepository.saveAll(options)
        }

        session.lastActivityAt = now
        locker.save(session)

        events.publishEvent(
            OrderRules.event(
                OrderChangeKind.BATCH_SUBMITTED, locked, order, savedBatch.id, savedItems,
                kitchen = !needsConfirmation, staff = true,
            ),
        )
        return view(locked.sessionId, replayed = false, batchId = savedBatch.id)
    }

    private class PreparedLine(val product: Product, val quantity: Int, val note: String?, val options: List<ValidatedOption>)

    /** Kiểm tra từng dòng (BR-O02, BR-O03, BE-18); nạp theo lô, không N+1. */
    private fun prepareLines(lines: List<PlaceOrderLine>, settings: RestaurantSettings): List<PreparedLine> {
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

    private fun view(sessionId: Long, replayed: Boolean, batchId: Long?): GuestOrderResponse =
        queryService.guestOrder(sessionId, replayed, batchId)

    companion object {
        val RATE_LIMIT_WINDOW: Duration = Duration.ofMinutes(1)
    }
}
