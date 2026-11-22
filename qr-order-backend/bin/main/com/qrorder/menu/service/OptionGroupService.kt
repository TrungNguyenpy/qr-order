package com.qrorder.menu.service

import com.qrorder.common.api.PageResponse
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.menu.domain.OptionGroup
import com.qrorder.menu.domain.OptionItem
import com.qrorder.menu.dto.ChangeActiveRequest
import com.qrorder.menu.dto.CreateOptionGroupRequest
import com.qrorder.menu.dto.CreateOptionItemRequest
import com.qrorder.menu.dto.OptionGroupResponse
import com.qrorder.menu.dto.OptionItemResponse
import com.qrorder.menu.dto.UpdateOptionGroupRequest
import com.qrorder.menu.dto.UpdateOptionItemRequest
import com.qrorder.menu.event.MenuChangeKind
import com.qrorder.menu.event.MenuChangedEvent
import com.qrorder.menu.event.MenuEntityType
import com.qrorder.menu.repository.MenuSpecifications
import com.qrorder.menu.repository.OptionGroupRepository
import com.qrorder.menu.repository.OptionItemRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Nhóm option/topping và lựa chọn (FR-PRD-03, BR-O03).
 *
 * - option_group: SINGLE/MULTIPLE, required, minSelect, maxSelect theo [OptionGroupRules] (trùng CHECK của DB).
 * - option_item: phụ thu `extraPrice` VND >= 0; tên duy nhất trong nhóm giữa các lựa chọn đang dùng
 *   (ux_option_item_group_name_active -> 409 OPTION_ITEM_NAME_TAKEN).
 * - Không xóa cứng (BR-A03): ngừng dùng bằng `active = false`. Đơn cũ không ảnh hưởng nhờ snapshot (BR-O01).
 * - Optimistic lock theo version client gửi (409); audit mọi thay đổi; phát MenuChangedEvent (/topic/menu sau commit).
 */
@Service
class OptionGroupService(
    private val groupRepository: OptionGroupRepository,
    private val itemRepository: OptionItemRepository,
    private val auditService: AuditService,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(readOnly = true)
    fun search(keyword: String?, active: Boolean?, pageable: Pageable): PageResponse<OptionGroupResponse> {
        val page = groupRepository.findAll(MenuSpecifications.optionGroups(keyword, active), pageable)
        val items = itemsByGroup(page.content.map { requireNotNull(it.id) })
        return PageResponse.of(page) { OptionGroupResponse.of(it, items[it.id].orEmpty()) }
    }

    @Transactional(readOnly = true)
    fun get(id: Long): OptionGroupResponse = toResponse(loadGroup(id))

    @Transactional
    fun create(actorUserId: Long, request: CreateOptionGroupRequest): OptionGroupResponse {
        val spec = OptionGroupRules.resolve(
            requireNotNull(request.selectionType), requireNotNull(request.required), request.minSelect, request.maxSelect,
        )
        val group = OptionGroup(requireNotNull(request.name).trim(), spec.selectionType).apply {
            required = spec.required
            minSelect = spec.minSelect.toShort()
            maxSelect = spec.maxSelect?.toShort()
            displayOrder = request.displayOrder ?: 0
        }
        val saved = groupRepository.saveAndFlush(group)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = MenuAuditActions.OPTION_GROUP_CREATED,
                entityType = MenuAuditEntityTypes.OPTION_GROUP,
                entityId = saved.id,
                newValue = groupSnapshot(saved),
            ),
        )
        publishGroup(saved, MenuChangeKind.CREATED)
        return toResponse(saved)
    }

    @Transactional
    fun update(actorUserId: Long, id: Long, request: UpdateOptionGroupRequest): OptionGroupResponse {
        val group = loadGroup(id)
        MenuGuards.requireVersion(request.version, group.version)
        val spec = OptionGroupRules.resolve(
            requireNotNull(request.selectionType), requireNotNull(request.required), request.minSelect, request.maxSelect,
        )
        val before = groupSnapshot(group)
        group.name = requireNotNull(request.name).trim()
        group.selectionType = spec.selectionType
        group.required = spec.required
        group.minSelect = spec.minSelect.toShort()
        group.maxSelect = spec.maxSelect?.toShort()
        group.displayOrder = requireNotNull(request.displayOrder)
        val after = groupSnapshot(group)
        val changed = MenuGuards.changedKeys(before, after)
        if (changed.isEmpty()) return toResponse(group)

        val saved = groupRepository.saveAndFlush(group)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = MenuAuditActions.OPTION_GROUP_UPDATED,
                entityType = MenuAuditEntityTypes.OPTION_GROUP,
                entityId = id,
                oldValue = before.filterKeys { it in changed },
                newValue = after.filterKeys { it in changed },
            ),
        )
        publishGroup(saved, MenuChangeKind.UPDATED)
        return toResponse(saved)
    }

    /** Ngừng dùng / dùng lại nhóm. Nhóm ngừng dùng không xuất hiện ở menu khách nhưng vẫn giữ liên kết với món. */
    @Transactional
    fun changeActive(actorUserId: Long, id: Long, request: ChangeActiveRequest): OptionGroupResponse {
        val group = loadGroup(id)
        MenuGuards.requireVersion(request.version, group.version)
        val target = requireNotNull(request.active)
        if (group.active == target) return toResponse(group)

        group.active = target
        val saved = groupRepository.saveAndFlush(group)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = MenuAuditActions.OPTION_GROUP_ACTIVE_CHANGED,
                entityType = MenuAuditEntityTypes.OPTION_GROUP,
                entityId = id,
                oldValue = mapOf("active" to !target),
                newValue = mapOf("active" to target),
            ),
        )
        publishGroup(saved, MenuChangeKind.ACTIVE_CHANGED)
        return toResponse(saved)
    }

    // ------------------------------------------------------------------------------------------ Option item

    @Transactional
    fun createItem(actorUserId: Long, groupId: Long, request: CreateOptionItemRequest): OptionItemResponse {
        loadGroup(groupId)
        val name = requireNotNull(request.name).trim()
        ensureItemNameAvailable(groupId, name, excludeId = NEW_ID)
        val item = OptionItem(groupId, name, request.extraPrice ?: 0).apply { displayOrder = request.displayOrder ?: 0 }
        val saved = itemRepository.saveAndFlush(item)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = MenuAuditActions.OPTION_ITEM_CREATED,
                entityType = MenuAuditEntityTypes.OPTION_ITEM,
                entityId = saved.id,
                newValue = itemSnapshot(saved) + ("optionGroupId" to groupId),
            ),
        )
        publishItem(saved, MenuChangeKind.CREATED)
        return OptionItemResponse.of(saved)
    }

    @Transactional
    fun updateItem(actorUserId: Long, groupId: Long, itemId: Long, request: UpdateOptionItemRequest): OptionItemResponse {
        val item = loadItem(groupId, itemId)
        MenuGuards.requireVersion(request.version, item.version)
        val name = requireNotNull(request.name).trim()
        if (item.active && !name.equals(item.name, ignoreCase = true)) ensureItemNameAvailable(groupId, name, excludeId = itemId)

        val before = itemSnapshot(item)
        item.name = name
        item.extraPrice = requireNotNull(request.extraPrice)
        item.displayOrder = requireNotNull(request.displayOrder)
        val after = itemSnapshot(item)
        val changed = MenuGuards.changedKeys(before, after)
        if (changed.isEmpty()) return OptionItemResponse.of(item)

        val saved = itemRepository.saveAndFlush(item)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = MenuAuditActions.OPTION_ITEM_UPDATED,
                entityType = MenuAuditEntityTypes.OPTION_ITEM,
                entityId = itemId,
                oldValue = before.filterKeys { it in changed },
                newValue = after.filterKeys { it in changed },
            ),
        )
        publishItem(saved, MenuChangeKind.UPDATED)
        return OptionItemResponse.of(saved)
    }

    /** Ngừng dùng / dùng lại lựa chọn; dùng lại bị chặn nếu đã có lựa chọn đang dùng trùng tên trong nhóm. */
    @Transactional
    fun changeItemActive(actorUserId: Long, groupId: Long, itemId: Long, request: ChangeActiveRequest): OptionItemResponse {
        val item = loadItem(groupId, itemId)
        MenuGuards.requireVersion(request.version, item.version)
        val target = requireNotNull(request.active)
        if (item.active == target) return OptionItemResponse.of(item)
        if (target) ensureItemNameAvailable(groupId, item.name, excludeId = itemId)

        item.active = target
        val saved = itemRepository.saveAndFlush(item)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = MenuAuditActions.OPTION_ITEM_ACTIVE_CHANGED,
                entityType = MenuAuditEntityTypes.OPTION_ITEM,
                entityId = itemId,
                oldValue = mapOf("active" to !target),
                newValue = mapOf("active" to target),
            ),
        )
        publishItem(saved, MenuChangeKind.ACTIVE_CHANGED)
        return OptionItemResponse.of(saved)
    }

    private fun toResponse(group: OptionGroup): OptionGroupResponse =
        OptionGroupResponse.of(group, itemsByGroup(listOf(requireNotNull(group.id)))[group.id].orEmpty())

    private fun itemsByGroup(groupIds: List<Long>): Map<Long, List<OptionItem>> =
        if (groupIds.isEmpty()) emptyMap()
        else itemRepository.findAllByOptionGroupIdInOrderByDisplayOrderAscIdAsc(groupIds).groupBy { it.optionGroupId }

    private fun loadGroup(id: Long): OptionGroup =
        groupRepository.findById(id).orElseThrow { ResourceNotFoundException("nhóm option", id) }

    /** Lựa chọn phải thuộc đúng nhóm trên URL; không thì 404 (không lộ lựa chọn của nhóm khác). */
    private fun loadItem(groupId: Long, itemId: Long): OptionItem =
        itemRepository.findByIdAndOptionGroupId(itemId, groupId) ?: throw ResourceNotFoundException("lựa chọn", itemId)

    private fun ensureItemNameAvailable(groupId: Long, name: String, excludeId: Long) {
        if (itemRepository.existsByOptionGroupIdAndActiveTrueAndNameIgnoreCaseAndIdNot(groupId, name, excludeId)) {
            throw BusinessException(ErrorCode.OPTION_ITEM_NAME_TAKEN)
        }
    }

    private fun groupSnapshot(g: OptionGroup): Map<String, Any?> = linkedMapOf(
        "name" to g.name,
        "selectionType" to g.selectionType,
        "required" to g.required,
        "minSelect" to g.minSelect.toInt(),
        "maxSelect" to g.maxSelect?.toInt(),
        "displayOrder" to g.displayOrder,
    )

    private fun itemSnapshot(i: OptionItem): Map<String, Any?> = linkedMapOf(
        "name" to i.name,
        "extraPrice" to i.extraPrice,
        "displayOrder" to i.displayOrder,
    )

    private fun publishGroup(g: OptionGroup, change: MenuChangeKind) =
        events.publishEvent(MenuChangedEvent(MenuEntityType.OPTION_GROUP, g.id, change, g.version))

    private fun publishItem(i: OptionItem, change: MenuChangeKind) =
        events.publishEvent(MenuChangedEvent(MenuEntityType.OPTION_ITEM, i.id, change, i.version))

    companion object {
        const val NEW_ID = -1L
    }
}
