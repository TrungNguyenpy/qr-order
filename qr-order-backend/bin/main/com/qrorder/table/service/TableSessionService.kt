package com.qrorder.table.service

import com.qrorder.auth.security.GuestPrincipal
import com.qrorder.auth.security.JwtService
import com.qrorder.common.api.PageResponse
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.domain.RequesterType
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.RateLimitExceededException
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.common.settings.RestaurantSettings
import com.qrorder.common.settings.RestaurantSettingsService
import com.qrorder.common.time.DbClock
import com.qrorder.table.domain.DiningTable
import com.qrorder.table.domain.QrCodeStatus
import com.qrorder.table.domain.SessionDevice
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.domain.TableSession
import com.qrorder.table.domain.TableStatus
import com.qrorder.table.dto.ForceCloseSessionRequest
import com.qrorder.table.dto.GuestSessionResponse
import com.qrorder.table.dto.GuestTableInfo
import com.qrorder.table.dto.GuestTokenResponse
import com.qrorder.table.dto.JoinSessionRequest
import com.qrorder.table.dto.StaffSessionResponse
import com.qrorder.table.event.SessionChangeKind
import com.qrorder.table.event.SessionChangedEvent
import com.qrorder.table.event.TableChangeKind
import com.qrorder.table.event.TableChangedEvent
import com.qrorder.table.qr.QrTokenCodec
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.QrCodeRepository
import com.qrorder.table.repository.SessionDeviceRepository
import com.qrorder.table.repository.TableSessionRepository
import com.qrorder.table.repository.TableSpecifications
import com.qrorder.table.session.JoinRateLimiter
import com.qrorder.table.session.SessionForceCloseContext
import com.qrorder.table.session.SessionForceCloseListener
import com.qrorder.table.session.SessionForceCloseValidator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Phiên bàn (Table Session - FR-SES-01..05, FR-AUTH-04, BR-S01..S09).
 *
 * Khóa: mọi thao tác thay đổi phiên/bàn khóa DÒNG BÀN trước (SELECT ... FOR UPDATE), sau đó mới khóa/nạp phiên.
 * Nhờ vậy các lần join đồng thời trên cùng một bàn chạy tuần tự: lần đầu tạo phiên OPEN, các lần sau thấy phiên đó và
 * gắn thiết bị vào (BR-S01); partial unique index ux_table_session_active_per_table là hàng rào cuối
 * (409 SESSION_ALREADY_ACTIVE). Thứ tự khóa thống nhất với force-close và Scheduler nên không deadlock.
 */
@Service
class TableSessionService(
    private val tableRepository: DiningTableRepository,
    private val qrCodeRepository: QrCodeRepository,
    private val sessionRepository: TableSessionRepository,
    private val deviceRepository: SessionDeviceRepository,
    private val settingsService: RestaurantSettingsService,
    private val codec: QrTokenCodec,
    private val jwtService: JwtService,
    private val rateLimiter: JoinRateLimiter,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
    private val forceCloseValidators: ObjectProvider<SessionForceCloseValidator>,
    private val forceCloseListeners: ObjectProvider<SessionForceCloseListener>,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val random = SecureRandom()

    // =========================================================================================== Khách (Guest)

    /**
     * POST /api/v1/public/sessions/join (FR-SES-01, §3.1). Thứ tự:
     * 1. Rate limit theo IP (BR-S09, join_rate_limit_per_minute) - ngoài transaction, đếm cả yêu cầu sai.
     * 2. Tra QR theo SHA-256 của token (BE-07: dùng index unique); phải ACTIVE -> ngược lại 404 QR_CODE_INVALID.
     * 3. Khóa dòng bàn; bàn ngừng dùng -> 409 TABLE_INACTIVE (BR-S08).
     * 4. Có phiên OPEN/BILLING: gắn thiết bị (BILLING chỉ xem). Chưa có: bàn phải AVAILABLE (đang dọn -> 409
     *    TABLE_NOT_READY), tạo phiên OPEN (GUEST), bàn -> OCCUPIED (§9.7 dòng 1); sinh PIN nếu require_table_pin.
     * 5. PIN gửi kèm (tùy chọn) phải đúng PIN của phiên -> sai 422 TABLE_PIN_INVALID (không thay đổi gì).
     * 6. Ghi session_device, cập nhật last_activity_at, cấp Guest JWT (TTL guest_jwt_ttl_minutes).
     */
    fun join(request: JoinSessionRequest, clientIp: String): GuestTokenResponse {
        val settings = settingsService.current()
        enforceRateLimit("join:$clientIp", settings.joinRateLimitPerMinute)
        val token = requireNotNull(request.qrToken).trim()
        val deviceId = requireNotNull(request.deviceId).trim()
        val result = requireNotNull(tx.execute { joinInTransaction(token, deviceId, request.pin) })
        return result
    }

    private fun joinInTransaction(qrToken: String, deviceId: String, pin: String?): GuestTokenResponse {
        val settings = settingsService.current()
        val qr = qrCodeRepository.findByTokenHash(codec.hashOf(qrToken))
        if (qr == null || qr.status != QrCodeStatus.ACTIVE) throw BusinessException(ErrorCode.QR_CODE_INVALID)
        val table = tableRepository.findByIdForUpdate(qr.tableId) ?: throw BusinessException(ErrorCode.QR_CODE_INVALID)
        if (!table.active) throw BusinessException(ErrorCode.TABLE_INACTIVE)

        val now = dbClock.now()
        var session = sessionRepository.findFirstByTableIdAndStatusIn(requireNotNull(table.id), TableGuards.ACTIVE_SESSION_STATUSES)
        val created = session == null
        if (session == null) {
            if (table.status != TableStatus.AVAILABLE) throw BusinessException(ErrorCode.TABLE_NOT_READY)
            session = openSession(table, RequesterType.GUEST, null, settings, now)
        } else {
            // Bật require_table_pin sau khi phiên đã mở: cấp PIN ngay để nhân viên đọc được cho khách.
            if (settings.requireTablePin && session.tablePin == null) session.tablePin = newPin()
            session.lastActivityAt = now
            session = sessionRepository.saveAndFlush(session)
        }

        val device = deviceRepository.findBySessionIdAndDeviceId(requireNotNull(session.id), deviceId)
            ?: SessionDevice(requireNotNull(session.id), deviceId)
        device.lastSeenAt = now
        if (pin != null && settings.requireTablePin) {
            // Phiên vừa tạo có PIN ngẫu nhiên khách chưa biết: PIN gửi kèm chắc chắn sai -> từ chối, không tạo gì.
            if (!pinMatches(session.tablePin, pin)) throw BusinessException(ErrorCode.TABLE_PIN_INVALID)
            if (device.pinVerifiedAt == null) device.pinVerifiedAt = now
        }
        val savedDevice = deviceRepository.saveAndFlush(device)

        if (created) publishOpened(table, session)
        return issueGuestToken(session, table, savedDevice, settings, newSession = created)
    }

    /** GET /api/v1/guest/session: phiên hiện tại của khách (Guest JWT đã được đối chiếu phiên còn hoạt động - BE-05). */
    @Transactional(readOnly = true)
    fun guestSession(principal: GuestPrincipal): GuestSessionResponse {
        val settings = settingsService.current()
        val session = loadSession(principal.sessionId)
        val table = loadTable(session.tableId)
        val device = deviceRepository.findBySessionIdAndDeviceId(principal.sessionId, principal.deviceId)
            ?: throw BusinessException(ErrorCode.TOKEN_INVALID)
        return guestView(session, table, device, settings)
    }

    /**
     * POST /api/v1/guest/session/token: gia hạn Guest JWT (FR-AUTH-04 "tự gia hạn khi phiên còn OPEN hoặc BILLING").
     * Token cũ phải còn hạn và phiên còn hoạt động (đã kiểm tra ở filter); hết hạn thì khách quét lại QR (join).
     */
    @Transactional
    fun refreshGuestToken(principal: GuestPrincipal): GuestTokenResponse {
        val settings = settingsService.current()
        val session = loadSession(principal.sessionId)
        if (!session.status.isActive) throw BusinessException(ErrorCode.SESSION_CLOSED)
        val table = loadTable(session.tableId)
        val device = deviceRepository.findBySessionIdAndDeviceId(principal.sessionId, principal.deviceId)
            ?: throw BusinessException(ErrorCode.TOKEN_INVALID)
        device.lastSeenAt = dbClock.now()
        return issueGuestToken(session, table, deviceRepository.saveAndFlush(device), settings, newSession = false)
    }

    /**
     * POST /api/v1/guest/session/verify-pin (DEC-07 A): khách nhập PIN 4 số do nhân viên cấp. Giới hạn số lần thử theo
     * phiên bằng cùng ngưỡng join_rate_limit_per_minute (chống dò PIN). Sai -> 422 TABLE_PIN_INVALID.
     */
    fun verifyPin(principal: GuestPrincipal, pin: String): GuestSessionResponse {
        val settings = settingsService.current()
        enforceRateLimit("pin:${principal.sessionId}", settings.joinRateLimitPerMinute)
        return requireNotNull(
            tx.execute {
                val session = loadSession(principal.sessionId)
                if (!session.status.isActive) throw BusinessException(ErrorCode.SESSION_CLOSED)
                val table = loadTable(session.tableId)
                val device = deviceRepository.findBySessionIdAndDeviceId(principal.sessionId, principal.deviceId)
                    ?: throw BusinessException(ErrorCode.TOKEN_INVALID)
                if (settings.requireTablePin && session.tablePin != null) {
                    if (!pinMatches(session.tablePin, pin)) throw BusinessException(ErrorCode.TABLE_PIN_INVALID)
                    if (device.pinVerifiedAt == null) {
                        device.pinVerifiedAt = dbClock.now()
                        deviceRepository.saveAndFlush(device)
                    }
                }
                guestView(session, table, device, settings)
            },
        )
    }

    // =========================================================================================== Nhân viên

    /**
     * FR-SES-02: nhân viên mở phiên hộ khách không dùng điện thoại. Bàn phải đang dùng, AVAILABLE và chưa có phiên
     * hoạt động (409 SESSION_ALREADY_ACTIVE). Trả chi tiết phiên kèm PIN (nếu bật) để nhân viên đọc cho khách.
     */
    @Transactional
    fun openByStaff(actorUserId: Long, tableId: Long): StaffSessionResponse {
        val settings = settingsService.current()
        val table = lockTable(tableId)
        if (!table.active) throw BusinessException(ErrorCode.TABLE_INACTIVE)
        if (sessionRepository.findFirstByTableIdAndStatusIn(tableId, TableGuards.ACTIVE_SESSION_STATUSES) != null) {
            throw BusinessException(ErrorCode.SESSION_ALREADY_ACTIVE)
        }
        if (table.status != TableStatus.AVAILABLE) throw BusinessException(ErrorCode.TABLE_NOT_READY)
        val session = openSession(table, RequesterType.STAFF, actorUserId, settings, dbClock.now())
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = TableAuditActions.SESSION_OPENED_BY_STAFF,
                entityType = TableAuditEntityTypes.TABLE_SESSION,
                entityId = session.id,
                newValue = mapOf("tableId" to tableId, "status" to session.status),
            ),
        )
        publishOpened(table, session)
        return StaffSessionResponse.of(session, table, 0)
    }

    /** Danh sách phiên (mặc định OPEN + BILLING), lọc theo bàn và cờ needs_attention; mới mở trước. */
    @Transactional(readOnly = true)
    fun search(statuses: Collection<SessionStatus>?, tableId: Long?, needsAttention: Boolean?, pageable: Pageable): PageResponse<StaffSessionResponse> {
        val filter = statuses?.takeIf { it.isNotEmpty() } ?: TableGuards.ACTIVE_SESSION_STATUSES
        val page = sessionRepository.findAll(TableSpecifications.sessions(filter, tableId, needsAttention), pageable)
        return toStaffPage(page.content.toList()).let { items ->
            PageResponse(items, page.number, page.size, page.totalElements, page.totalPages)
        }
    }

    /** FR-SES-06: danh sách phiên cần xử lý (idle quá hạn còn đơn chưa thanh toán), cũ trước. */
    @Transactional(readOnly = true)
    fun needsAttention(): List<StaffSessionResponse> =
        toStaffPage(
            sessionRepository.findAll(TableSpecifications.sessions(TableGuards.ACTIVE_SESSION_STATUSES, null, true))
                .sortedWith(compareBy<TableSession> { it.attentionSetAt }.thenBy { it.id }),
        )

    @Transactional(readOnly = true)
    fun detail(id: Long): StaffSessionResponse {
        val session = loadSession(id)
        return StaffSessionResponse.of(session, tableRepository.findById(session.tableId).orElse(null), deviceRepository.countBySessionId(id))
    }

    /**
     * POST /api/v1/sessions/{id}/force-close (BR-S06, FR-SES-03). Cần SESSION_FORCE_CLOSE (kiểm tra ở controller).
     * 1. Khóa bàn rồi khóa phiên; phiên phải OPEN/BILLING (409 SESSION_FINAL_STATE); version (nếu gửi) phải khớp.
     * 2. Gọi mọi [SessionForceCloseValidator] (GĐ5-GĐ6: Bill VOID_PENDING, Payment PENDING -> từ chối).
     * 3. Gọi mọi [SessionForceCloseListener] (GĐ4-GĐ6: món PENDING -> REJECTED, Order/Bill -> WRITTEN_OFF/VOIDED).
     * 4. Phiên -> FORCE_CLOSED (closed_at theo đồng hồ DB, closed_by, close_reason, xóa needs_attention); bàn -> CLEANING
     *    (AVAILABLE nếu cleaning_auto_release); audit SESSION_FORCE_CLOSED kèm lý do. Guest JWT của phiên bị từ chối ngay.
     */
    @Transactional
    fun forceClose(actorUserId: Long, id: Long, request: ForceCloseSessionRequest): StaffSessionResponse {
        val reason = requireNotNull(request.reason).trim()
        val tableId = sessionRepository.findTableIdById(id) ?: throw ResourceNotFoundException("phiên bàn", id)
        val table = lockTable(tableId)
        val session = sessionRepository.findByIdForUpdate(id) ?: throw ResourceNotFoundException("phiên bàn", id)
        if (!session.status.isActive) throw BusinessException(ErrorCode.SESSION_FINAL_STATE)
        if (request.version != null) TableGuards.requireVersion(request.version, session.version)

        val context = SessionForceCloseContext(id, tableId, session.status, actorUserId, reason)
        forceCloseValidators.orderedStream().forEach { it.validate(context) }
        forceCloseListeners.orderedStream().forEach { it.onForceClose(context) }

        val settings = settingsService.current()
        val previous = session.status
        val closed = closeSession(table, session, SessionStatus.FORCE_CLOSED, actorUserId, reason, settings)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = TableAuditActions.SESSION_FORCE_CLOSED,
                entityType = TableAuditEntityTypes.TABLE_SESSION,
                entityId = id,
                oldValue = mapOf("status" to previous, "tableStatus" to closed.previousTableStatus),
                newValue = mapOf("status" to SessionStatus.FORCE_CLOSED, "tableStatus" to table.status),
                reason = reason,
            ),
        )
        return StaffSessionResponse.of(closed.session, table, deviceRepository.countBySessionId(id))
    }

    // =========================================================================================== Dùng chung (Scheduler)

    /** Kết quả đóng phiên: phiên sau khi lưu và trạng thái bàn trước đó (cho audit). */
    class ClosedSession(val session: TableSession, val previousTableStatus: TableStatus)

    /**
     * Đóng phiên (trạng thái cuối) và đồng bộ bàn trong CÙNG transaction (BR-S08, §9.7). Bàn và phiên phải đã được
     * khóa. EXPIRED -> bàn AVAILABLE (dòng 18); FORCE_CLOSED / CLOSED -> CLEANING, hoặc AVAILABLE nếu
     * cleaning_auto_release (§9.5). closed_at lấy theo đồng hồ DB (BE-03, CHECK closed_at >= opened_at).
     */
    fun closeSession(
        table: DiningTable,
        session: TableSession,
        target: SessionStatus,
        closedBy: Long?,
        reason: String?,
        settings: RestaurantSettings,
    ): ClosedSession {
        require(!target.isActive) { "closeSession chỉ dùng cho trạng thái cuối" }
        val now = dbClock.now()
        session.status = target
        session.closedAt = now
        session.closedBy = closedBy
        session.closeReason = reason
        session.needsAttention = false
        session.attentionReason = null
        session.attentionSetAt = null
        val saved = sessionRepository.saveAndFlush(session)

        val previousTableStatus = table.status
        val nextTableStatus = when {
            target == SessionStatus.EXPIRED -> TableStatus.AVAILABLE
            settings.cleaningAutoRelease -> TableStatus.AVAILABLE
            else -> TableStatus.CLEANING
        }
        if (table.status != nextTableStatus) {
            table.status = nextTableStatus
            table.statusChangedAt = now
        }
        val savedTable = tableRepository.saveAndFlush(table)
        events.publishEvent(
            SessionChangedEvent(requireNotNull(saved.id), saved.tableId, savedTable.code, saved.status, saved.version, SessionChangeKind.CLOSED),
        )
        publishTable(savedTable, sessionId = null)
        return ClosedSession(saved, previousTableStatus)
    }

    // =========================================================================================== nội bộ

    private fun openSession(
        table: DiningTable,
        openedBy: RequesterType,
        openedByUserId: Long?,
        settings: RestaurantSettings,
        now: OffsetDateTime,
    ): TableSession {
        val session = TableSession(requireNotNull(table.id), openedBy, openedByUserId).apply {
            lastActivityAt = now
            if (settings.requireTablePin) tablePin = newPin()
        }
        val saved = sessionRepository.saveAndFlush(session)
        table.status = TableStatus.OCCUPIED
        table.statusChangedAt = now
        tableRepository.saveAndFlush(table)
        return saved
    }

    private fun publishOpened(table: DiningTable, session: TableSession) {
        events.publishEvent(
            SessionChangedEvent(requireNotNull(session.id), session.tableId, table.code, session.status, session.version, SessionChangeKind.OPENED),
        )
        publishTable(table, session.id)
    }

    private fun publishTable(table: DiningTable, sessionId: Long?) {
        events.publishEvent(
            TableChangedEvent(requireNotNull(table.id), table.code, table.status, table.active, table.version, TableChangeKind.STATUS_CHANGED, sessionId),
        )
    }

    private fun issueGuestToken(
        session: TableSession,
        table: DiningTable,
        device: SessionDevice,
        settings: RestaurantSettings,
        newSession: Boolean,
    ): GuestTokenResponse {
        val issued = jwtService.issueGuestToken(
            requireNotNull(session.id), device.deviceId, Duration.ofMinutes(settings.guestJwtTtlMinutes.toLong()),
        )
        return GuestTokenResponse(
            accessToken = issued.token,
            expiresAt = issued.expiresAt,
            newSession = newSession,
            session = guestView(session, table, device, settings),
        )
    }

    private fun guestView(session: TableSession, table: DiningTable, device: SessionDevice, settings: RestaurantSettings): GuestSessionResponse {
        val pinRequired = settings.requireTablePin && session.tablePin != null
        return GuestSessionResponse(
            sessionId = requireNotNull(session.id),
            status = session.status,
            canOrder = session.status == SessionStatus.OPEN,
            openedAt = session.openedAt,
            table = GuestTableInfo(requireNotNull(table.id), table.code, table.displayName, table.area),
            deviceId = device.deviceId,
            pinRequired = pinRequired,
            pinVerified = !pinRequired || device.pinVerifiedAt != null,
        )
    }

    private fun toStaffPage(sessions: List<TableSession>): List<StaffSessionResponse> {
        if (sessions.isEmpty()) return emptyList()
        val tables = tableRepository.findAllById(sessions.map { it.tableId }.distinct()).associateBy { it.id }
        val counts = deviceRepository.countBySessionIds(sessions.mapNotNull { it.id }).associate { it.sessionId to it.total }
        return sessions.map { StaffSessionResponse.of(it, tables[it.tableId], counts[it.id] ?: 0) }
    }

    private fun enforceRateLimit(key: String, limitPerMinute: Int) {
        val decision = rateLimiter.tryAcquire(key, limitPerMinute, RATE_LIMIT_WINDOW)
        if (!decision.allowed) throw RateLimitExceededException(decision.retryAfterSeconds)
    }

    private fun pinMatches(expected: String?, given: String): Boolean =
        expected != null && MessageDigest.isEqual(expected.trim().toByteArray(), given.trim().toByteArray())

    private fun newPin(): String = "%04d".format(random.nextInt(PIN_SPACE))

    private fun loadSession(id: Long): TableSession =
        sessionRepository.findById(id).orElseThrow { ResourceNotFoundException("phiên bàn", id) }

    private fun loadTable(id: Long): DiningTable =
        tableRepository.findById(id).orElseThrow { ResourceNotFoundException("bàn", id) }

    private fun lockTable(id: Long): DiningTable =
        tableRepository.findByIdForUpdate(id) ?: throw ResourceNotFoundException("bàn", id)

    companion object {
        val RATE_LIMIT_WINDOW: Duration = Duration.ofMinutes(1)
        private const val PIN_SPACE = 10_000
    }
}
