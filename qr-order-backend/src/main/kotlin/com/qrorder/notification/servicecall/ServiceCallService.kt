package com.qrorder.notification.servicecall

import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.security.GuestPrincipal
import com.qrorder.auth.security.StaffPrincipal
import com.qrorder.common.domain.RequesterType
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.common.time.DbClock
import com.qrorder.notification.domain.ServiceCall
import com.qrorder.notification.domain.ServiceCallStatus
import com.qrorder.notification.domain.ServiceCallType
import com.qrorder.order.service.LockedSession
import com.qrorder.order.service.SessionOrderLocker
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.TableSessionRepository
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

// =============================================================================================== Repository

interface ServiceCallRepository : JpaRepository<ServiceCall, Long> {
    fun findFirstBySessionIdAndTypeAndStatusIn(sessionId: Long, type: ServiceCallType, statuses: Collection<ServiceCallStatus>): ServiceCall?

    fun findAllBySessionIdAndStatusInOrderByIdAsc(sessionId: Long, statuses: Collection<ServiceCallStatus>): List<ServiceCall>

    fun findAllBySessionIdOrderByIdDesc(sessionId: Long): List<ServiceCall>

    /** Hàng đợi "Cần xử lý" (ix_service_call_open_queue): cũ trước. */
    fun findAllByStatusInOrderByCreatedAtAscIdAsc(statuses: Collection<ServiceCallStatus>): List<ServiceCall>

    @Query("select c.sessionId from ServiceCall c where c.id = :id")
    fun findSessionIdById(@Param("id") id: Long): Long?
}

// =============================================================================================== DTO

private const val NOTE_MAX = 500

/** Khách gửi yêu cầu: CALL_STAFF (gọi nhân viên) hoặc REQUEST_PAYMENT (yêu cầu thanh toán - DEC-08 B). */
data class GuestServiceCallRequest(
    @field:NotNull(message = "Loại yêu cầu là bắt buộc (CALL_STAFF hoặc REQUEST_PAYMENT)")
    val type: ServiceCallType?,
    @field:Size(max = NOTE_MAX, message = "Ghi chú tối đa 500 ký tự")
    val note: String? = null,
)

/** Staff gửi Manager hỗ trợ (MANAGER_SUPPORT) cho một phiên. */
data class StaffServiceCallRequest(
    @field:NotNull(message = "sessionId là bắt buộc")
    val sessionId: Long?,
    @field:Size(max = NOTE_MAX, message = "Ghi chú tối đa 500 ký tự")
    val note: String? = null,
)

data class ResolveServiceCallRequest(
    @field:Size(max = NOTE_MAX, message = "Ghi chú tối đa 500 ký tự")
    val note: String? = null,
)

/** Phản hồi cho nhân viên. [autoResolved] = hệ thống tự RESOLVED (resolved_by NULL), ví dụ khi Tính tiền. */
data class ServiceCallResponse(
    val id: Long,
    val sessionId: Long,
    val tableId: Long?,
    val tableCode: String?,
    val type: ServiceCallType,
    val status: ServiceCallStatus,
    val createdByType: RequesterType,
    val createdByUserId: Long?,
    val note: String?,
    val createdAt: OffsetDateTime?,
    val acknowledgedBy: Long?,
    val acknowledgedAt: OffsetDateTime?,
    val resolvedBy: Long?,
    val resolvedAt: OffsetDateTime?,
    val resolutionNote: String?,
    val autoResolved: Boolean,
    val version: Long,
)

/** Phản hồi cho khách: không chứa thông tin nhân viên. [replayed] = đã có yêu cầu cùng loại đang mở (trả lại yêu cầu đó). */
data class GuestServiceCallResponse(
    val id: Long,
    val type: ServiceCallType,
    val status: ServiceCallStatus,
    val note: String?,
    val createdAt: OffsetDateTime?,
    val acknowledgedAt: OffsetDateTime?,
    val resolvedAt: OffsetDateTime?,
    val replayed: Boolean = false,
)

// =============================================================================================== Event

enum class ServiceCallChangeKind { CREATED, ACKNOWLEDGED, RESOLVED }

/** ServiceCall thay đổi -> `/topic/staff/orders` (mọi loại) và `/topic/session/{sessionId}` (loại do khách tạo). */
data class ServiceCallChangedEvent(
    val kind: ServiceCallChangeKind,
    val callId: Long,
    val sessionId: Long,
    val tableId: Long,
    val tableCode: String,
    val type: ServiceCallType,
    val status: ServiceCallStatus,
    val autoResolved: Boolean,
)

// =============================================================================================== Service

/**
 * ServiceCall (FR-NOT-03, §9.8, DEC-08 B):
 * - Khách (Guest JWT): CALL_STAFF khi phiên OPEN/BILLING; REQUEST_PAYMENT chỉ khi phiên OPEN (BILLING nghĩa là nhân viên
 *   đã tính tiền). Khách không bao giờ khóa Order - chỉ nhân viên Tính tiền mới khóa (DEC-08 B).
 * - Staff/Manager: MANAGER_SUPPORT cho phiên đang hoạt động (Staff gửi Manager, BR-P16).
 * - Mỗi (phiên, loại) tối đa một yêu cầu OPEN/ACKNOWLEDGED (ux_service_call_open_per_type): gửi lại khi đã có yêu cầu
 *   đang mở thì trả lại yêu cầu đó (200, không tạo mới). Hai request song song: khóa bàn -> phiên tuần tự hóa; index
 *   unique là hàng rào cuối (409 SERVICE_CALL_ALREADY_OPEN).
 * - OPEN -> ACKNOWLEDGED -> RESOLVED (hoặc OPEN -> RESOLVED). CALL_STAFF/REQUEST_PAYMENT: STAFF hoặc MANAGER xử lý;
 *   MANAGER_SUPPORT: chỉ MANAGER (§9.8).
 * - Tự RESOLVED (resolved_by NULL): REQUEST_PAYMENT khi Tính tiền; mọi yêu cầu còn mở khi phiên đóng (CLOSED,
 *   FORCE_CLOSED, EXPIRED) để không treo trong hàng đợi.
 * Mọi thay đổi phát sự kiện, notifier đẩy STOMP SAU COMMIT.
 */
@Service
class ServiceCallService(
    private val repository: ServiceCallRepository,
    private val locker: SessionOrderLocker,
    private val sessionRepository: TableSessionRepository,
    private val tableRepository: DiningTableRepository,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun guestCreate(principal: GuestPrincipal, request: GuestServiceCallRequest): GuestServiceCallResponse {
        val type = requireNotNull(request.type)
        if (type == ServiceCallType.MANAGER_SUPPORT) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "Khách chỉ gửi được CALL_STAFF hoặc REQUEST_PAYMENT")
        }
        val locked = locker.lock(principal.sessionId)
        val status = locked.session.status
        if (!status.isActive) throw BusinessException(ErrorCode.SESSION_CLOSED)
        if (type == ServiceCallType.REQUEST_PAYMENT && status != SessionStatus.OPEN) {
            throw BusinessException(ErrorCode.SERVICE_CALL_STATE_INVALID, "Nhân viên đã tính tiền cho bàn, vui lòng chờ thanh toán")
        }
        repository.findFirstBySessionIdAndTypeAndStatusIn(locked.sessionId, type, OPEN_STATUSES)?.let {
            return guestView(it, replayed = true)
        }
        val call = ServiceCall(locked.sessionId, type, RequesterType.GUEST).apply {
            createdByDeviceId = principal.deviceId
            note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        }
        val saved = repository.saveAndFlush(call)
        locked.session.lastActivityAt = dbClock.now()
        locker.save(locked.session)
        publish(ServiceCallChangeKind.CREATED, saved, locked)
        return guestView(saved, replayed = false)
    }

    @Transactional(readOnly = true)
    fun guestList(sessionId: Long): List<GuestServiceCallResponse> =
        repository.findAllBySessionIdOrderByIdDesc(sessionId).map { guestView(it, replayed = false) }

    /** Staff tạo MANAGER_SUPPORT (Staff hoặc Manager). Trả (yêu cầu, đã có từ trước). */
    @Transactional
    fun staffCreate(actor: StaffPrincipal, request: StaffServiceCallRequest): Pair<ServiceCallResponse, Boolean> {
        val sessionId = requireNotNull(request.sessionId)
        val locked = locker.lock(sessionId)
        if (!locked.session.status.isActive) throw BusinessException(ErrorCode.SESSION_FINAL_STATE)
        repository.findFirstBySessionIdAndTypeAndStatusIn(sessionId, ServiceCallType.MANAGER_SUPPORT, OPEN_STATUSES)?.let {
            return view(it, locked.tableId, locked.table.code) to true
        }
        val call = ServiceCall(sessionId, ServiceCallType.MANAGER_SUPPORT, RequesterType.STAFF).apply {
            createdByUserId = actor.userId
            note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        }
        val saved = repository.saveAndFlush(call)
        publish(ServiceCallChangeKind.CREATED, saved, locked)
        return view(saved, locked.tableId, locked.table.code) to false
    }

    @Transactional
    fun acknowledge(actor: StaffPrincipal, id: Long): ServiceCallResponse {
        val (locked, call) = lockCall(id)
        requireHandler(actor, call)
        if (call.status == ServiceCallStatus.RESOLVED) {
            throw BusinessException(ErrorCode.SERVICE_CALL_STATE_INVALID, "Yêu cầu đã được xử lý xong")
        }
        // Đã ACKNOWLEDGED: trả lại trạng thái hiện tại (idempotent), không đổi người nhận.
        if (call.status == ServiceCallStatus.ACKNOWLEDGED) return view(call, locked.tableId, locked.table.code)
        call.status = ServiceCallStatus.ACKNOWLEDGED
        call.acknowledgedBy = actor.userId
        call.acknowledgedAt = dbClock.now()
        val saved = repository.saveAndFlush(call)
        publish(ServiceCallChangeKind.ACKNOWLEDGED, saved, locked)
        return view(saved, locked.tableId, locked.table.code)
    }

    @Transactional
    fun resolve(actor: StaffPrincipal, id: Long, request: ResolveServiceCallRequest?): ServiceCallResponse {
        val (locked, call) = lockCall(id)
        requireHandler(actor, call)
        if (call.status == ServiceCallStatus.RESOLVED) throw BusinessException(ErrorCode.SERVICE_CALL_STATE_INVALID, "Yêu cầu đã được xử lý xong")
        call.status = ServiceCallStatus.RESOLVED
        call.resolvedBy = actor.userId
        call.resolvedAt = dbClock.now()
        call.resolutionNote = request?.note?.trim()?.takeIf { it.isNotEmpty() }
        val saved = repository.saveAndFlush(call)
        publish(ServiceCallChangeKind.RESOLVED, saved, locked)
        return view(saved, locked.tableId, locked.table.code)
    }

    /** Hàng đợi cho Staff/Manager (mặc định OPEN + ACKNOWLEDGED), cũ trước. */
    @Transactional(readOnly = true)
    fun queue(statuses: Collection<ServiceCallStatus>?): List<ServiceCallResponse> {
        val calls = repository.findAllByStatusInOrderByCreatedAtAscIdAsc(statuses?.takeIf { it.isNotEmpty() } ?: OPEN_STATUSES)
        if (calls.isEmpty()) return emptyList()
        val sessions = sessionRepository.findAllById(calls.map { it.sessionId }.distinct()).associateBy { it.id }
        val tables = tableRepository.findAllById(sessions.values.map { it.tableId }.distinct()).associateBy { it.id }
        return calls.map { c ->
            val table = sessions[c.sessionId]?.let { tables[it.tableId] }
            view(c, table?.id, table?.code)
        }
    }

    /**
     * Hệ thống tự RESOLVED các yêu cầu còn mở thuộc [types] của phiên (resolved_by NULL - CHECK ck_service_call_lifecycle
     * cho phép). Phải gọi trong transaction đã khóa bàn + phiên. Trả danh sách đã đóng.
     */
    fun autoResolve(sessionId: Long, tableId: Long, tableCode: String, types: Collection<ServiceCallType>, note: String): List<ServiceCall> {
        val open = repository.findAllBySessionIdAndStatusInOrderByIdAsc(sessionId, OPEN_STATUSES).filter { it.type in types }
        if (open.isEmpty()) return emptyList()
        val now = dbClock.now()
        open.forEach {
            it.status = ServiceCallStatus.RESOLVED
            it.resolvedBy = null
            it.resolvedAt = now
            it.resolutionNote = note
        }
        val saved = repository.saveAllAndFlush(open)
        saved.forEach { publish(ServiceCallChangeKind.RESOLVED, it, sessionId, tableId, tableCode) }
        return saved
    }

    // =========================================================================================== nội bộ

    private fun lockCall(id: Long): Pair<LockedSession, ServiceCall> {
        val sessionId = repository.findSessionIdById(id) ?: throw ResourceNotFoundException("yêu cầu hỗ trợ", id)
        val locked = locker.lock(sessionId)
        val call = repository.findById(id).orElseThrow { ResourceNotFoundException("yêu cầu hỗ trợ", id) }
        return locked to call
    }

    /** §9.8: MANAGER_SUPPORT do Manager nhận và xử lý; các loại khác Staff hoặc Manager. */
    private fun requireHandler(actor: StaffPrincipal, call: ServiceCall) {
        if (call.type == ServiceCallType.MANAGER_SUPPORT && actor.role != UserRole.MANAGER) {
            throw BusinessException(ErrorCode.PERMISSION_DENIED, "Chỉ Manager xử lý yêu cầu hỗ trợ của nhân viên")
        }
    }

    private fun publish(kind: ServiceCallChangeKind, call: ServiceCall, locked: LockedSession) =
        publish(kind, call, locked.sessionId, locked.tableId, locked.table.code)

    private fun publish(kind: ServiceCallChangeKind, call: ServiceCall, sessionId: Long, tableId: Long, tableCode: String) {
        events.publishEvent(
            ServiceCallChangedEvent(
                kind = kind, callId = requireNotNull(call.id), sessionId = sessionId, tableId = tableId, tableCode = tableCode,
                type = call.type, status = call.status, autoResolved = call.status == ServiceCallStatus.RESOLVED && call.resolvedBy == null,
            ),
        )
    }

    private fun view(c: ServiceCall, tableId: Long?, tableCode: String?) = ServiceCallResponse(
        id = requireNotNull(c.id), sessionId = c.sessionId, tableId = tableId, tableCode = tableCode, type = c.type, status = c.status,
        createdByType = c.createdByType, createdByUserId = c.createdByUserId, note = c.note, createdAt = c.createdAt,
        acknowledgedBy = c.acknowledgedBy, acknowledgedAt = c.acknowledgedAt, resolvedBy = c.resolvedBy, resolvedAt = c.resolvedAt,
        resolutionNote = c.resolutionNote, autoResolved = c.status == ServiceCallStatus.RESOLVED && c.resolvedBy == null,
        version = c.version,
    )

    private fun guestView(c: ServiceCall, replayed: Boolean) = GuestServiceCallResponse(
        id = requireNotNull(c.id), type = c.type, status = c.status, note = c.note, createdAt = c.createdAt,
        acknowledgedAt = c.acknowledgedAt, resolvedAt = c.resolvedAt, replayed = replayed,
    )

    companion object {
        val OPEN_STATUSES: Set<ServiceCallStatus> = setOf(ServiceCallStatus.OPEN, ServiceCallStatus.ACKNOWLEDGED)
    }
}
