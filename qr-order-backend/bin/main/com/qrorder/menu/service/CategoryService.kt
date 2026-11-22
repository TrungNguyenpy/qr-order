package com.qrorder.menu.service

import com.qrorder.common.api.ErrorDetail
import com.qrorder.common.api.PageResponse
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.menu.domain.Category
import com.qrorder.menu.dto.CategoryResponse
import com.qrorder.menu.dto.ChangeCategoryVisibilityRequest
import com.qrorder.menu.dto.CreateCategoryRequest
import com.qrorder.menu.dto.ReorderCategoriesRequest
import com.qrorder.menu.dto.UpdateCategoryRequest
import com.qrorder.menu.event.MenuChangeKind
import com.qrorder.menu.event.MenuChangedEvent
import com.qrorder.menu.event.MenuEntityType
import com.qrorder.menu.repository.CategoryRepository
import com.qrorder.menu.repository.MenuSpecifications
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Danh mục món (FR-CAT-01): CRUD, tìm kiếm, phân trang, sắp xếp display_order, ẩn/hiện.
 *
 * - Không xóa cứng (BR-A03, trigger trg_category_no_delete): ẩn bằng `visible = false`.
 * - Optimistic lock theo version client gửi (409), @Version là hàng rào cuối.
 * - Tên duy nhất không phân biệt hoa thường: kiểm tra sớm, unique index ux_category_name_lower là hàng rào cuối
 *   (-> 409 CATEGORY_NAME_TAKEN).
 * - Mọi thay đổi ghi audit cùng transaction và phát [MenuChangedEvent] (đẩy /topic/menu sau commit).
 */
@Service
class CategoryService(
    private val repository: CategoryRepository,
    private val auditService: AuditService,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(readOnly = true)
    fun search(keyword: String?, visible: Boolean?, pageable: Pageable): PageResponse<CategoryResponse> =
        PageResponse.of(repository.findAll(MenuSpecifications.categories(keyword, visible), pageable)) { CategoryResponse.of(it) }

    @Transactional(readOnly = true)
    fun get(id: Long): CategoryResponse = CategoryResponse.of(load(id))

    /** Dùng cho ProductService: danh mục tồn tại không (để báo lỗi tham chiếu). */
    @Transactional(readOnly = true)
    fun exists(id: Long): Boolean = repository.existsById(id)

    @Transactional
    fun create(actorUserId: Long, request: CreateCategoryRequest): CategoryResponse {
        val name = requireNotNull(request.name).trim()
        ensureNameAvailable(name, excludeId = NEW_ID)
        val category = Category(name).apply {
            description = MenuGuards.optionalText(request.description)
            displayOrder = request.displayOrder ?: 0
            visible = request.visible ?: true
        }
        val saved = repository.saveAndFlush(category)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = MenuAuditActions.CATEGORY_CREATED,
                entityType = MenuAuditEntityTypes.CATEGORY,
                entityId = saved.id,
                newValue = snapshot(saved) + ("visible" to saved.visible),
            ),
        )
        publish(saved, MenuChangeKind.CREATED)
        return CategoryResponse.of(saved)
    }

    @Transactional
    fun update(actorUserId: Long, id: Long, request: UpdateCategoryRequest): CategoryResponse {
        val category = load(id)
        MenuGuards.requireVersion(request.version, category.version)
        val name = requireNotNull(request.name).trim()
        if (!name.equals(category.name, ignoreCase = true)) ensureNameAvailable(name, excludeId = id)

        val before = snapshot(category)
        category.name = name
        category.description = MenuGuards.optionalText(request.description)
        category.displayOrder = requireNotNull(request.displayOrder)
        val after = snapshot(category)
        val changed = MenuGuards.changedKeys(before, after)
        if (changed.isEmpty()) return CategoryResponse.of(category)

        val saved = repository.saveAndFlush(category)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = MenuAuditActions.CATEGORY_UPDATED,
                entityType = MenuAuditEntityTypes.CATEGORY,
                entityId = id,
                oldValue = before.filterKeys { it in changed },
                newValue = after.filterKeys { it in changed },
            ),
        )
        publish(saved, MenuChangeKind.UPDATED)
        return CategoryResponse.of(saved)
    }

    /** Ẩn/hiện danh mục; gửi giá trị đang có thì không đổi gì (idempotent). */
    @Transactional
    fun changeVisibility(actorUserId: Long, id: Long, request: ChangeCategoryVisibilityRequest): CategoryResponse {
        val category = load(id)
        MenuGuards.requireVersion(request.version, category.version)
        val target = requireNotNull(request.visible)
        if (category.visible == target) return CategoryResponse.of(category)

        category.visible = target
        val saved = repository.saveAndFlush(category)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = MenuAuditActions.CATEGORY_VISIBILITY_CHANGED,
                entityType = MenuAuditEntityTypes.CATEGORY,
                entityId = id,
                oldValue = mapOf("visible" to !target),
                newValue = mapOf("visible" to target),
            ),
        )
        publish(saved, MenuChangeKind.VISIBILITY_CHANGED)
        return CategoryResponse.of(saved)
    }

    /**
     * Sắp xếp lại nhiều danh mục trong một transaction: mọi id phải tồn tại và đúng version, nếu không thì
     * không đổi gì (404 / 409). Trả về toàn bộ danh mục đã gửi theo thứ tự mới.
     */
    @Transactional
    fun reorder(actorUserId: Long, request: ReorderCategoriesRequest): List<CategoryResponse> {
        val items = requireNotNull(request.items)
        val ids = items.map { requireNotNull(it.id) }
        if (ids.toSet().size != ids.size) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, details = listOf(ErrorDetail("items", "Danh mục bị lặp trong danh sách")))
        }
        val categories = repository.findAllById(ids).associateBy { requireNotNull(it.id) }
        ids.firstOrNull { it !in categories }?.let { throw ResourceNotFoundException("danh mục", it) }

        val oldOrder = LinkedHashMap<String, Any?>()
        val newOrder = LinkedHashMap<String, Any?>()
        items.forEach { item ->
            val category = categories.getValue(requireNotNull(item.id))
            MenuGuards.requireVersion(item.version, category.version)
            val target = requireNotNull(item.displayOrder)
            if (category.displayOrder != target) {
                oldOrder[category.id.toString()] = category.displayOrder
                newOrder[category.id.toString()] = target
                category.displayOrder = target
            }
        }
        if (newOrder.isNotEmpty()) {
            repository.saveAllAndFlush(categories.values)
            auditService.record(
                AuditActor.User(actorUserId),
                AuditEvent(
                    action = MenuAuditActions.CATEGORIES_REORDERED,
                    entityType = MenuAuditEntityTypes.CATEGORY,
                    entityId = null,
                    oldValue = mapOf("displayOrder" to oldOrder),
                    newValue = mapOf("displayOrder" to newOrder),
                ),
            )
            events.publishEvent(MenuChangedEvent(MenuEntityType.CATEGORY, null, MenuChangeKind.REORDERED))
        }
        return ids.map { CategoryResponse.of(categories.getValue(it)) }
    }

    private fun load(id: Long): Category = repository.findById(id).orElseThrow { ResourceNotFoundException("danh mục", id) }

    private fun ensureNameAvailable(name: String, excludeId: Long) {
        if (repository.existsByNameIgnoreCaseAndIdNot(name, excludeId)) throw BusinessException(ErrorCode.CATEGORY_NAME_TAKEN)
    }

    private fun snapshot(c: Category): Map<String, Any?> = linkedMapOf(
        "name" to c.name,
        "description" to c.description,
        "displayOrder" to c.displayOrder,
    )

    private fun publish(c: Category, change: MenuChangeKind) =
        events.publishEvent(MenuChangedEvent(MenuEntityType.CATEGORY, c.id, change, c.version))

    companion object {
        /** Giá trị id không tồn tại dùng cho kiểm tra trùng tên khi tạo mới. */
        const val NEW_ID = -1L
    }
}
