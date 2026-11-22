package com.qrorder.table.service

import com.qrorder.common.api.PageResponse
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.common.time.DbClock
import com.qrorder.table.domain.DiningTable
import com.qrorder.table.domain.QrCodeStatus
import com.qrorder.table.domain.TableStatus
import com.qrorder.table.dto.ActiveSessionSummary
import com.qrorder.table.dto.ChangeTableActiveRequest
import com.qrorder.table.dto.ChangeTableStatusRequest
import com.qrorder.table.dto.CreateTableRequest
import com.qrorder.table.dto.FloorPlanTableResponse
import com.qrorder.table.dto.TableResponse
import com.qrorder.table.dto.UpdateTableRequest
import com.qrorder.table.event.TableChangeKind
import com.qrorder.table.event.TableChangedEvent
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.QrCodeRepository
import com.qrorder.table.repository.SessionDeviceRepository
import com.qrorder.table.repository.TableSessionRepository
import com.qrorder.table.repository.TableSpecifications
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Bàn ăn (FR-TBL-01..03, DEC-04 B, BR-S08).
 *
 * - Mã bàn duy nhất không phân biệt hoa thường: kiểm tra sớm, unique index ux_dining_table_code_lower là hàng rào cuối
 *   (409 TABLE_CODE_TAKEN).
 * - Optimistic lock theo version client gửi (409), @Version là hàng rào cuối.
 * - Không xóa cứng (BR-A03, trigger trg_dining_table_no_delete): ngừng dùng bằng `active = false`, chỉ khi bàn
 *   AVAILABLE và không có phiên OPEN/BILLING (CHECK ck_dining_table_inactive_only_available là hàng rào cuối).
 * - Trạng thái bàn do vòng đời phiên quyết định (§9.7); thao tác tay duy nhất là xác nhận dọn xong CLEANING -> AVAILABLE.
 * - Mọi thao tác đổi trạng thái/ngừng dùng khóa dòng bàn (FOR UPDATE) để tuần tự với join phiên.
 * - Mọi thay đổi ghi audit cùng transaction và phát [TableChangedEvent] (đẩy /topic/tables sau commit).
 */
@Service
class DiningTableService(
    private val repository: DiningTableRepository,
    private val qrCodeRepository: QrCodeRepository,
    private val sessionRepository: TableSessionRepository,
    private val deviceRepository: SessionDeviceRepository,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(readOnly = true)
    fun search(keyword: String?, area: String?, status: TableStatus?, active: Boolean?, pageable: Pageable): PageResponse<TableResponse> =
        PageResponse.of(repository.findAll(TableSpecifications.tables(keyword, area, status, active), pageable)) { TableResponse.of(it) }

    @Transactional(readOnly = true)
    fun get(id: Long): TableResponse = TableResponse.of(load(id))

    /** FR-TBL-02: toàn bộ bàn đang dùng kèm phiên hoạt động và cờ có QR (4 truy vấn cố định, không N+1). */
    @Transactional(readOnly = true)
    fun floorPlan(): List<FloorPlanTableResponse> {
        val tables = repository.findAllByActiveTrueOrderByAreaAscDisplayOrderAscIdAsc()
        if (tables.isEmpty()) return emptyList()
        val ids = tables.map { requireNotNull(it.id) }
        val withQr = qrCodeRepository.findAllByTableIdInAndStatus(ids, QrCodeStatus.ACTIVE).map { it.tableId }.toSet()
        val sessions = sessionRepository.findAllByTableIdInAndStatusIn(ids, TableGuards.ACTIVE_SESSION_STATUSES).associateBy { it.tableId }
        val deviceCounts = sessions.values.mapNotNull { it.id }.takeIf { it.isNotEmpty() }
            ?.let { sessionIds -> deviceRepository.countBySessionIds(sessionIds).associate { it.sessionId to it.total } }
            ?: emptyMap()
        return tables.map { table ->
            val session = sessions[table.id]
            FloorPlanTableResponse(
                table = TableResponse.of(table),
                hasActiveQr = table.id in withQr,
                activeSession = session?.let {
                    ActiveSessionSummary(
                        id = requireNotNull(it.id),
                        status = it.status,
                        openedAt = it.openedAt,
                        lastActivityAt = it.lastActivityAt,
                        needsAttention = it.needsAttention,
                        deviceCount = deviceCounts[it.id] ?: 0,
                        version = it.version,
                    )
                },
            )
        }
    }

    @Transactional
    fun create(actorUserId: Long, request: CreateTableRequest): TableResponse {
        val code = requireNotNull(request.code).trim()
        if (repository.existsByCodeIgnoreCase(code)) throw BusinessException(ErrorCode.TABLE_CODE_TAKEN)
        val table = DiningTable(
            code = code,
            displayName = requireNotNull(request.displayName).trim(),
            capacity = requireNotNull(request.capacity).toShort(),
        ).apply {
            area = TableGuards.optionalText(request.area)
            displayOrder = request.displayOrder ?: 0
            statusChangedAt = dbClock.now()
        }
        val saved = repository.saveAndFlush(table)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = TableAuditActions.TABLE_CREATED,
                entityType = TableAuditEntityTypes.DINING_TABLE,
                entityId = saved.id,
                newValue = snapshot(saved) + mapOf("status" to saved.status, "active" to saved.active),
            ),
        )
        publish(saved, TableChangeKind.CREATED)
        return TableResponse.of(saved)
    }

    @Transactional
    fun update(actorUserId: Long, id: Long, request: UpdateTableRequest): TableResponse {
        val table = load(id)
        TableGuards.requireVersion(request.version, table.version)
        val code = requireNotNull(request.code).trim()
        if (!code.equals(table.code, ignoreCase = true) && repository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw BusinessException(ErrorCode.TABLE_CODE_TAKEN)
        }
        val before = snapshot(table)
        table.code = code
        table.displayName = requireNotNull(request.displayName).trim()
        table.area = TableGuards.optionalText(request.area)
        table.capacity = requireNotNull(request.capacity).toShort()
        table.displayOrder = requireNotNull(request.displayOrder)
        val after = snapshot(table)
        val changed = TableGuards.changedKeys(before, after)
        if (changed.isEmpty()) return TableResponse.of(table)

        val saved = repository.saveAndFlush(table)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = TableAuditActions.TABLE_UPDATED,
                entityType = TableAuditEntityTypes.DINING_TABLE,
                entityId = id,
                oldValue = before.filterKeys { it in changed },
                newValue = after.filterKeys { it in changed },
            ),
        )
        publish(saved, TableChangeKind.UPDATED)
        return TableResponse.of(saved)
    }

    /**
     * Ngừng sử dụng / dùng lại bàn (BR-S08). Ngừng dùng chỉ khi bàn AVAILABLE và không có phiên OPEN/BILLING
     * (khóa dòng bàn để không chen ngang một lần join đang chạy). Gửi giá trị đang có thì không đổi gì.
     * QR của bàn giữ nguyên (bàn ngừng dùng đã bị chặn join); muốn vô hiệu tem QR thì thu hồi QR riêng.
     */
    @Transactional
    fun changeActive(actorUserId: Long, id: Long, request: ChangeTableActiveRequest): TableResponse {
        val table = lockForUpdate(id)
        TableGuards.requireVersion(request.version, table.version)
        val target = requireNotNull(request.active)
        if (table.active == target) return TableResponse.of(table)
        if (!target) {
            val busy = table.status != TableStatus.AVAILABLE ||
                sessionRepository.findFirstByTableIdAndStatusIn(id, TableGuards.ACTIVE_SESSION_STATUSES) != null
            if (busy) throw BusinessException(ErrorCode.TABLE_IN_USE)
        }
        table.active = target
        val saved = repository.saveAndFlush(table)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = TableAuditActions.TABLE_ACTIVE_CHANGED,
                entityType = TableAuditEntityTypes.DINING_TABLE,
                entityId = id,
                oldValue = mapOf("active" to !target),
                newValue = mapOf("active" to target),
            ),
        )
        publish(saved, TableChangeKind.ACTIVE_CHANGED)
        return TableResponse.of(saved)
    }

    /**
     * FR-TBL-03, §9.5: nhân viên xác nhận dọn xong CLEANING -> AVAILABLE. Các trạng thái khác (OCCUPIED,
     * PAYMENT_PENDING, CLEANING) do vòng đời phiên đặt (§9.7) nên không cho đổi tay -> 409 INVALID_TABLE_TRANSITION.
     * Gửi đúng trạng thái đang có thì không đổi gì (bấm lặp an toàn).
     */
    @Transactional
    fun changeStatus(actorUserId: Long, id: Long, request: ChangeTableStatusRequest): TableResponse {
        val table = lockForUpdate(id)
        TableGuards.requireVersion(request.version, table.version)
        val target = requireNotNull(request.status)
        if (table.status == target) return TableResponse.of(table)
        val allowed = table.status == TableStatus.CLEANING && target == TableStatus.AVAILABLE
        if (!allowed) {
            throw BusinessException(
                ErrorCode.INVALID_TABLE_TRANSITION,
                "Chỉ được xác nhận dọn xong (CLEANING -> AVAILABLE); trạng thái khác do phiên bàn quyết định",
            )
        }
        // Bàn CLEANING không nhận join nên không thể có phiên hoạt động; kiểm tra lại để không làm lệch §9.7.
        if (sessionRepository.findFirstByTableIdAndStatusIn(id, TableGuards.ACTIVE_SESSION_STATUSES) != null) {
            throw BusinessException(ErrorCode.TABLE_IN_USE)
        }
        val previous = table.status
        table.status = target
        table.statusChangedAt = dbClock.now()
        val saved = repository.saveAndFlush(table)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = TableAuditActions.TABLE_STATUS_CHANGED,
                entityType = TableAuditEntityTypes.DINING_TABLE,
                entityId = id,
                oldValue = mapOf("status" to previous),
                newValue = mapOf("status" to target),
            ),
        )
        publish(saved, TableChangeKind.STATUS_CHANGED)
        return TableResponse.of(saved)
    }

    private fun load(id: Long): DiningTable = repository.findById(id).orElseThrow { ResourceNotFoundException("bàn", id) }

    private fun lockForUpdate(id: Long): DiningTable = repository.findByIdForUpdate(id) ?: throw ResourceNotFoundException("bàn", id)

    private fun snapshot(t: DiningTable): Map<String, Any?> = linkedMapOf(
        "code" to t.code,
        "displayName" to t.displayName,
        "area" to t.area,
        "capacity" to t.capacity.toInt(),
        "displayOrder" to t.displayOrder,
    )

    private fun publish(t: DiningTable, change: TableChangeKind) {
        events.publishEvent(
            TableChangedEvent(
                tableId = requireNotNull(t.id),
                code = t.code,
                status = t.status,
                active = t.active,
                version = t.version,
                change = change,
            ),
        )
    }
}
