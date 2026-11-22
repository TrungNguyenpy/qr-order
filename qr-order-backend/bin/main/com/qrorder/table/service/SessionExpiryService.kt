package com.qrorder.table.service

import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.settings.RestaurantSettingsService
import com.qrorder.common.time.DbClock
import com.qrorder.table.config.SessionProperties
import com.qrorder.table.domain.SessionAttentionReason
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.event.SessionChangeKind
import com.qrorder.table.event.SessionChangedEvent
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.TableSessionRepository
import com.qrorder.table.session.SessionExpiryListener
import com.qrorder.table.session.UnpaidOrderChecker
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/** Kết quả một lượt quét (cho log và test). */
data class SessionExpiryResult(
    val scanned: Int,
    val expired: List<Long>,
    val flagged: List<Long>,
    val skipped: Int,
    val failed: Int,
)

/**
 * Quét phiên idle (BR-S07, DEC-05 A, UC36, FR-SES-06). Mỗi lượt:
 * - Chọn phiên OPEN có last_activity_at quá session_idle_timeout_minutes (restaurant_settings) theo đồng hồ DB.
 *   Phiên BILLING không bao giờ tự hết hạn.
 * - Xử lý TỪNG phiên trong transaction riêng (một phiên lỗi không ảnh hưởng phiên khác): khóa bàn rồi khóa phiên,
 *   kiểm tra lại điều kiện (khách có thể vừa thao tác), rồi:
 *   - Không còn đơn chưa thanh toán ([UnpaidOrderChecker]): gọi [SessionExpiryListener] (Order rỗng -> VOIDED), phiên
 *     -> EXPIRED, bàn -> AVAILABLE (§9.7 dòng 18), audit SESSION_EXPIRED (actor SYSTEM).
 *   - Còn đơn chưa thanh toán: KHÔNG đóng phiên; đặt needs_attention (IDLE_WITH_UNPAID_ORDER) một lần, audit và phát
 *     thông báo kênh nhân viên `/topic/staff/orders`. Lượt sau gặp lại phiên đã gắn cờ thì bỏ qua (không báo lặp);
 *     nếu đơn đã được xử lý hết thì phiên được EXPIRED và cờ được xóa.
 */
@Service
class SessionExpiryService(
    private val sessionRepository: TableSessionRepository,
    private val tableRepository: DiningTableRepository,
    private val sessionService: TableSessionService,
    private val settingsService: RestaurantSettingsService,
    private val unpaidOrderChecker: UnpaidOrderChecker,
    private val expiryListeners: ObjectProvider<SessionExpiryListener>,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
    private val properties: SessionProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    fun runOnce(): SessionExpiryResult {
        val idleMinutes = settingsService.current().sessionIdleTimeoutMinutes
        val ids = sessionRepository.findIdleOpenSessionIds(idleMinutes, properties.expiryBatchSize.coerceAtLeast(1))
        val expired = mutableListOf<Long>()
        val flagged = mutableListOf<Long>()
        var skipped = 0
        var failed = 0
        ids.forEach { id ->
            try {
                when (tx.execute { processOne(id) }) {
                    Outcome.EXPIRED -> expired += id
                    Outcome.FLAGGED -> flagged += id
                    else -> skipped++
                }
            } catch (ex: Exception) {
                failed++
                log.warn("Không xử lý được phiên {} khi quét hết hạn: {}", id, ex.message)
            }
        }
        if (expired.isNotEmpty() || flagged.isNotEmpty() || failed > 0) {
            log.info("Quét phiên idle: {} ứng viên, {} EXPIRED, {} cần xử lý, {} bỏ qua, {} lỗi", ids.size, expired.size, flagged.size, skipped, failed)
        }
        return SessionExpiryResult(ids.size, expired, flagged, skipped, failed)
    }

    private enum class Outcome { EXPIRED, FLAGGED, SKIPPED }

    private fun processOne(sessionId: Long): Outcome {
        val settings = settingsService.current()
        val tableId = sessionRepository.findTableIdById(sessionId) ?: return Outcome.SKIPPED
        val table = tableRepository.findByIdForUpdate(tableId) ?: return Outcome.SKIPPED
        val session = sessionRepository.findByIdForUpdate(sessionId) ?: return Outcome.SKIPPED
        val now = dbClock.now()
        val idleLimit = Duration.ofMinutes(settings.sessionIdleTimeoutMinutes.toLong())
        // Kiểm tra lại sau khi có khóa: phiên có thể vừa chuyển BILLING/đóng hoặc khách vừa thao tác.
        if (session.status != SessionStatus.OPEN || !session.lastActivityAt.plus(idleLimit).isBefore(now)) return Outcome.SKIPPED

        if (unpaidOrderChecker.hasUnpaidOrder(sessionId)) {
            if (session.needsAttention) return Outcome.SKIPPED
            session.needsAttention = true
            session.attentionReason = SessionAttentionReason.IDLE_WITH_UNPAID_ORDER
            session.attentionSetAt = now
            val saved = sessionRepository.saveAndFlush(session)
            auditService.record(
                AuditActor.System,
                AuditEvent(
                    action = TableAuditActions.SESSION_NEEDS_ATTENTION,
                    entityType = TableAuditEntityTypes.TABLE_SESSION,
                    entityId = sessionId,
                    newValue = mapOf("needsAttention" to true, "attentionReason" to SessionAttentionReason.IDLE_WITH_UNPAID_ORDER),
                ),
            )
            events.publishEvent(
                SessionChangedEvent(sessionId, tableId, table.code, saved.status, saved.version, SessionChangeKind.NEEDS_ATTENTION),
            )
            return Outcome.FLAGGED
        }

        expiryListeners.orderedStream().forEach { it.onExpire(sessionId) }
        val closed = sessionService.closeSession(table, session, SessionStatus.EXPIRED, closedBy = null, reason = null, settings = settings)
        auditService.record(
            AuditActor.System,
            AuditEvent(
                action = TableAuditActions.SESSION_EXPIRED,
                entityType = TableAuditEntityTypes.TABLE_SESSION,
                entityId = sessionId,
                oldValue = mapOf("status" to SessionStatus.OPEN, "tableStatus" to closed.previousTableStatus),
                newValue = mapOf("status" to SessionStatus.EXPIRED, "tableStatus" to table.status, "idleMinutes" to settings.sessionIdleTimeoutMinutes),
            ),
        )
        return Outcome.EXPIRED
    }
}

/**
 * Chạy [SessionExpiryService] định kỳ (mặc định 60 giây, `app.session.expiry-check-interval-ms`). fixedDelay: lượt sau
 * chỉ bắt đầu khi lượt trước xong. Chỉ chạy khi bật scheduling (`app.scheduling.enabled`, mặc định true).
 */
@Component
class SessionExpiryScheduler(private val service: SessionExpiryService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${app.session.expiry-check-interval-ms:60000}",
        initialDelayString = "\${app.session.expiry-check-interval-ms:60000}",
    )
    fun scan() {
        try {
            service.runOnce()
        } catch (ex: Exception) {
            log.error("Lượt quét phiên hết hạn thất bại", ex)
        }
    }
}
