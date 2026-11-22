package com.qrorder.menu.service

import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.security.StaffPrincipal
import com.qrorder.common.api.ErrorDetail
import com.qrorder.common.api.PageResponse
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.menu.domain.OptionGroup
import com.qrorder.menu.domain.Product
import com.qrorder.menu.domain.ProductOptionGroup
import com.qrorder.menu.domain.ProductOptionGroupId
import com.qrorder.menu.domain.ProductStatus
import com.qrorder.menu.dto.AssignedOptionGroupResponse
import com.qrorder.menu.dto.ChangeProductStatusRequest
import com.qrorder.menu.dto.CreateProductRequest
import com.qrorder.menu.dto.ProductResponse
import com.qrorder.menu.dto.UpdateProductOptionGroupsRequest
import com.qrorder.menu.dto.UpdateProductRequest
import com.qrorder.menu.event.MenuChangeKind
import com.qrorder.menu.event.MenuChangedEvent
import com.qrorder.menu.event.MenuEntityType
import com.qrorder.menu.event.ProductSoldOutEvent
import com.qrorder.menu.repository.CategoryRepository
import com.qrorder.menu.repository.MenuSpecifications
import com.qrorder.menu.repository.OptionGroupRepository
import com.qrorder.menu.repository.ProductOptionGroupRepository
import com.qrorder.menu.repository.ProductRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Món ăn (FR-PRD-01..04): CRUD, tìm kiếm/lọc theo danh mục và trạng thái, giá, trạng thái thủ công, gán nhóm option.
 *
 * - Không xóa cứng (BR-A03, trigger trg_product_no_delete): ẩn bằng HIDDEN.
 * - Giá VND đã gồm VAT, >= 0 (CHECK ck_product_price). Đổi giá ghi audit PRODUCT_PRICE_CHANGED (giá cũ/mới);
 *   đơn cũ không bị ảnh hưởng vì order_item snapshot giá (BR-O01).
 * - Trạng thái (FR-PRD-02): AVAILABLE <-> SOLD_OUT do Bếp/Staff/Manager/Admin; mọi chuyển liên quan HIDDEN chỉ
 *   ADMIN/MANAGER. Chuyển sang SOLD_OUT phát [ProductSoldOutEvent] (BR-O13, module order xử lý ở GĐ4).
 * - Ảnh: [ProductImageService].
 */
@Service
class ProductService(
    private val repository: ProductRepository,
    private val categoryRepository: CategoryRepository,
    private val optionGroupRepository: OptionGroupRepository,
    private val productOptionGroupRepository: ProductOptionGroupRepository,
    private val auditService: AuditService,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Transactional(readOnly = true)
    fun search(keyword: String?, categoryId: Long?, status: ProductStatus?, pageable: Pageable): PageResponse<ProductResponse> =
        PageResponse.of(repository.findAll(MenuSpecifications.products(keyword, categoryId, status), pageable)) { ProductResponse.of(it) }

    @Transactional(readOnly = true)
    fun get(id: Long): ProductResponse = toDetail(load(id))

    @Transactional
    fun create(actorUserId: Long, request: CreateProductRequest): ProductResponse {
        val categoryId = requireNotNull(request.categoryId)
        ensureCategoryExists(categoryId)
        val product = Product(
            categoryId = categoryId,
            name = requireNotNull(request.name).trim(),
            price = requireNotNull(request.price),
        ).apply {
            description = MenuGuards.optionalText(request.description)
            displayOrder = request.displayOrder ?: 0
            status = request.status ?: ProductStatus.AVAILABLE
            statusChangedAt = OffsetDateTime.now(clock)
            statusChangedBy = actorUserId
        }
        val saved = repository.saveAndFlush(product)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = MenuAuditActions.PRODUCT_CREATED,
                entityType = MenuAuditEntityTypes.PRODUCT,
                entityId = saved.id,
                newValue = infoSnapshot(saved) + mapOf("price" to saved.price, "status" to saved.status),
            ),
        )
        publish(saved, MenuChangeKind.CREATED)
        return toDetail(saved)
    }

    /** Sửa thông tin và giá. Chỉ đổi giá -> sự kiện PRICE_CHANGED; có đổi thông tin khác -> UPDATED. */
    @Transactional
    fun update(actorUserId: Long, id: Long, request: UpdateProductRequest): ProductResponse {
        val product = load(id)
        MenuGuards.requireVersion(request.version, product.version)
        val categoryId = requireNotNull(request.categoryId)
        if (categoryId != product.categoryId) ensureCategoryExists(categoryId)

        val beforeInfo = infoSnapshot(product)
        val oldPrice = product.price
        product.categoryId = categoryId
        product.name = requireNotNull(request.name).trim()
        product.description = MenuGuards.optionalText(request.description)
        product.displayOrder = requireNotNull(request.displayOrder)
        product.price = requireNotNull(request.price)
        val afterInfo = infoSnapshot(product)
        val changedInfo = MenuGuards.changedKeys(beforeInfo, afterInfo)
        val priceChanged = oldPrice != product.price
        if (changedInfo.isEmpty() && !priceChanged) return toDetail(product)

        val saved = repository.saveAndFlush(product)
        val actor = AuditActor.User(actorUserId)
        if (changedInfo.isNotEmpty()) {
            auditService.record(
                actor,
                AuditEvent(
                    action = MenuAuditActions.PRODUCT_UPDATED,
                    entityType = MenuAuditEntityTypes.PRODUCT,
                    entityId = id,
                    oldValue = beforeInfo.filterKeys { it in changedInfo },
                    newValue = afterInfo.filterKeys { it in changedInfo },
                ),
            )
        }
        if (priceChanged) {
            auditService.record(
                actor,
                AuditEvent(
                    action = MenuAuditActions.PRODUCT_PRICE_CHANGED,
                    entityType = MenuAuditEntityTypes.PRODUCT,
                    entityId = id,
                    oldValue = mapOf("price" to oldPrice),
                    newValue = mapOf("price" to saved.price),
                ),
            )
        }
        publish(saved, if (changedInfo.isEmpty()) MenuChangeKind.PRICE_CHANGED else MenuChangeKind.UPDATED)
        return toDetail(saved)
    }

    /**
     * Đổi trạng thái thủ công (FR-PRD-02, UC25). Gửi trạng thái đang có thì không đổi gì (bấm lặp an toàn).
     * Sang SOLD_OUT: phát [ProductSoldOutEvent] sau khi lưu, trong cùng transaction (BR-O13).
     */
    @Transactional
    fun changeStatus(actor: StaffPrincipal, id: Long, request: ChangeProductStatusRequest): ProductResponse {
        val target = requireNotNull(request.status)
        val product = load(id)
        val current = product.status
        if ((target == ProductStatus.HIDDEN || current == ProductStatus.HIDDEN) && target != current && actor.role !in MENU_MANAGERS) {
            throw BusinessException(ErrorCode.PERMISSION_DENIED, "Chỉ ADMIN hoặc MANAGER được ẩn/bỏ ẩn món")
        }
        MenuGuards.requireVersion(request.version, product.version)
        if (current == target) return toDetail(product)

        product.status = target
        product.statusChangedAt = OffsetDateTime.now(clock)
        product.statusChangedBy = actor.userId
        val saved = repository.saveAndFlush(product)
        auditService.record(
            AuditActor.User(actor.userId),
            AuditEvent(
                action = MenuAuditActions.PRODUCT_STATUS_CHANGED,
                entityType = MenuAuditEntityTypes.PRODUCT,
                entityId = id,
                oldValue = mapOf("status" to current),
                newValue = mapOf("status" to target),
                reason = request.reason,
            ),
        )
        publish(saved, MenuChangeKind.STATUS_CHANGED)
        if (target == ProductStatus.SOLD_OUT) events.publishEvent(ProductSoldOutEvent(id, actor.userId))
        return toDetail(saved)
    }

    /**
     * Gán/gỡ/sắp xếp nhóm option của món (FR-PRD-03): [UpdateProductOptionGroupsRequest.optionGroupIds] là danh sách
     * đầy đủ theo thứ tự. Nhóm mới gán phải đang dùng (active); nhóm đã gán trước đó mà nay ngừng dùng được giữ.
     * Tăng version của món để thay đổi đồng thời bị phát hiện (409).
     */
    @Transactional
    fun updateOptionGroups(actorUserId: Long, id: Long, request: UpdateProductOptionGroupsRequest): ProductResponse {
        val product = load(id)
        MenuGuards.requireVersion(request.version, product.version)
        val requested = requireNotNull(request.optionGroupIds)
        if (requested.toSet().size != requested.size) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                details = listOf(ErrorDetail("optionGroupIds", "Nhóm option bị lặp trong danh sách")),
            )
        }
        val groups = optionGroupRepository.findAllById(requested).associateBy { requireNotNull(it.id) }
        val missing = requested.filter { it !in groups }
        if (missing.isNotEmpty()) {
            throw BusinessException(
                ErrorCode.INVALID_REFERENCE,
                "Nhóm option không tồn tại: ${missing.joinToString()}",
                listOf(ErrorDetail("optionGroupIds", "Không tồn tại: ${missing.joinToString()}")),
            )
        }
        val existing = productOptionGroupRepository.findAllByIdProductIdOrderByDisplayOrderAsc(id)
        val existingIds = existing.map { it.id.optionGroupId }
        val inactiveNew = requested.filter { it !in existingIds && !groups.getValue(it).active }
        if (inactiveNew.isNotEmpty()) {
            throw BusinessException(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                "Không gán được nhóm option đã ngừng dùng: ${inactiveNew.joinToString()}",
            )
        }
        if (existingIds == requested) return toDetail(product)

        val byGroup = existing.associateBy { it.id.optionGroupId }
        val removed = existing.filter { it.id.optionGroupId !in requested }
        productOptionGroupRepository.deleteAll(removed)
        requested.forEachIndexed { index, groupId ->
            val order = index + 1
            val link = byGroup[groupId]
            if (link == null) {
                productOptionGroupRepository.save(ProductOptionGroup(ProductOptionGroupId(id, groupId), order))
            } else if (link.displayOrder != order) {
                link.displayOrder = order
            }
        }
        if (repository.bumpVersion(id, product.version) != 1) throw BusinessException(ErrorCode.CONCURRENT_MODIFICATION)
        entityManager.refresh(product)

        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = MenuAuditActions.PRODUCT_OPTION_GROUPS_CHANGED,
                entityType = MenuAuditEntityTypes.PRODUCT,
                entityId = id,
                oldValue = mapOf("optionGroupIds" to existingIds),
                newValue = mapOf("optionGroupIds" to requested),
            ),
        )
        publish(product, MenuChangeKind.OPTIONS_CHANGED)
        return toDetail(product)
    }

    /** Món kèm nhóm option đã gán (dùng chung với ProductImageService, chạy trong transaction của bên gọi). */
    fun toDetail(product: Product): ProductResponse {
        val links = productOptionGroupRepository.findAllByIdProductIdOrderByDisplayOrderAsc(requireNotNull(product.id))
        val groups: Map<Long, OptionGroup> =
            optionGroupRepository.findAllById(links.map { it.id.optionGroupId }).associateBy { requireNotNull(it.id) }
        val assigned = links.mapNotNull { link ->
            groups[link.id.optionGroupId]?.let { g ->
                AssignedOptionGroupResponse(
                    optionGroupId = requireNotNull(g.id),
                    name = g.name,
                    selectionType = g.selectionType,
                    required = g.required,
                    active = g.active,
                    displayOrder = link.displayOrder,
                )
            }
        }
        return ProductResponse.of(product, assigned)
    }

    /** Phát MenuChangedEvent cho món (đẩy /topic/menu sau commit). */
    fun publish(p: Product, change: MenuChangeKind) =
        events.publishEvent(MenuChangedEvent(MenuEntityType.PRODUCT, p.id, change, p.version, p.status, p.price))

    private fun load(id: Long): Product = repository.findById(id).orElseThrow { ResourceNotFoundException("món", id) }

    private fun ensureCategoryExists(categoryId: Long) {
        if (!categoryRepository.existsById(categoryId)) {
            throw BusinessException(
                ErrorCode.INVALID_REFERENCE,
                "Danh mục #$categoryId không tồn tại",
                listOf(ErrorDetail("categoryId", "Danh mục không tồn tại")),
            )
        }
    }

    private fun infoSnapshot(p: Product): Map<String, Any?> = linkedMapOf(
        "categoryId" to p.categoryId,
        "name" to p.name,
        "description" to p.description,
        "displayOrder" to p.displayOrder,
    )

    companion object {
        /** Role quản lý menu (ẩn/bỏ ẩn món, CRUD). Theo xác nhận Giai đoạn 2: ADMIN và MANAGER. */
        val MENU_MANAGERS: Set<UserRole> = setOf(UserRole.ADMIN, UserRole.MANAGER)
    }
}
