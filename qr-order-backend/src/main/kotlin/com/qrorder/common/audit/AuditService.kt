package com.qrorder.common.audit

import com.qrorder.common.domain.AuditActorType
import com.qrorder.common.web.ClientRequestInfo
import com.qrorder.common.web.CorrelationId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.BigInteger
import java.time.temporal.TemporalAccessor
import java.util.UUID

interface AuditLogRepository : JpaRepository<AuditLog, Long>

/** Người thực hiện thao tác được ghi audit (audit_log.actor_type, actor_user_id). */
sealed interface AuditActor {
    data class User(val userId: Long) : AuditActor
    data object Guest : AuditActor
    data object System : AuditActor
}

/**
 * Danh mục action (DATABASE_DESIGN.md §6, bổ sung Giai đoạn 1). action là UPPER_SNAKE_CASE
 * (CHECK ck_audit_log_action); không khóa cứng danh sách ở database.
 */
object AuditActions {
    const val USER_CREATED = "USER_CREATED"
    const val USER_UPDATED = "USER_UPDATED"
    const val USER_LOCKED = "USER_LOCKED"
    const val USER_UNLOCKED = "USER_UNLOCKED"
    const val USER_DISABLED = "USER_DISABLED"
    const val USER_ROLE_CHANGED = "USER_ROLE_CHANGED"
    const val USER_PASSWORD_CHANGED = "USER_PASSWORD_CHANGED"
    const val USER_PASSWORD_RESET = "USER_PASSWORD_RESET"
    const val PERMISSION_GRANTED = "PERMISSION_GRANTED"
    const val PERMISSION_REVOKED = "PERMISSION_REVOKED"
    const val SETTINGS_CHANGED = "SETTINGS_CHANGED"

    /** Các action bắt buộc có lý do theo CHECK ck_audit_log_reason_required và ck_audit_log_role_change. */
    val REASON_REQUIRED: Set<String> = setOf(
        "BILL_DISCOUNT_APPLIED", "BILL_VOID_REQUESTED", "BILL_VOID_WITHDRAWN",
        "SESSION_FORCE_CLOSED", "ORDER_WRITTEN_OFF", PERMISSION_GRANTED, PERMISSION_REVOKED,
        "REFUND_CREATED", USER_ROLE_CHANGED,
    )
}

/** Tên bảng dùng làm audit_log.entity_type (CHECK ck_audit_log_entity_type: chữ thường, snake_case). */
object AuditEntityTypes {
    const val USERS = "users"
    const val USER_PERMISSION = "user_permission"
    const val RESTAURANT_SETTINGS = "restaurant_settings"
}

/**
 * Một bản ghi audit cần ghi. [oldValue]/[newValue] là giá trị trước/sau của các trường bị đổi;
 * giá trị được chuẩn hóa sang kiểu JSON đơn giản trước khi lưu (enum -> tên, thời gian -> ISO-8601).
 */
data class AuditEvent(
    val action: String,
    val entityType: String,
    val entityId: Long?,
    val oldValue: Map<String, Any?>? = null,
    val newValue: Map<String, Any?>? = null,
    val reason: String? = null,
    val reauthenticated: Boolean = false,
)

/**
 * Ghi audit_log dùng chung cho mọi module (BR-A05, NFR-10).
 *
 * - Bắt buộc chạy TRONG transaction của thao tác nghiệp vụ (Propagation.MANDATORY): audit và thay đổi
 *   cùng commit hoặc cùng rollback; gọi ngoài transaction là lỗi lập trình.
 * - Tự điền correlation_id (MDC của request) và ip_address (request HTTP hiện tại, nếu có).
 * - Kiểm tra trước các CHECK của audit_log để lỗi lập trình lộ ra rõ ràng thay vì lỗi database.
 * - Không bao giờ lưu trường nhạy cảm: khóa có chứa "password", "secret", "token" bị loại khỏi
 *   old_value/new_value (quy tắc 7, NFR-04).
 */
@Service
class AuditService(
    private val repository: AuditLogRepository,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(actor: AuditActor, event: AuditEvent): AuditLog {
        val reason = event.reason?.trim()?.takeIf { it.isNotEmpty() }
        require(ACTION_FORMAT.matches(event.action)) { "audit action không hợp lệ: ${event.action}" }
        require(ENTITY_TYPE_FORMAT.matches(event.entityType)) { "audit entity_type không hợp lệ: ${event.entityType}" }
        require(!event.reauthenticated || actor is AuditActor.User) { "Chỉ USER mới có thể xác thực lại" }
        require(event.action !in AuditActions.REASON_REQUIRED || reason != null) { "Action ${event.action} bắt buộc có lý do" }
        require(event.action != AuditActions.USER_ROLE_CHANGED || event.reauthenticated) {
            "USER_ROLE_CHANGED bắt buộc xác thực lại (BR-A08)"
        }

        val log = AuditLog(
            actorType = when (actor) {
                is AuditActor.User -> AuditActorType.USER
                AuditActor.Guest -> AuditActorType.GUEST
                AuditActor.System -> AuditActorType.SYSTEM
            },
            action = event.action,
            entityType = event.entityType,
        ).apply {
            actorUserId = (actor as? AuditActor.User)?.userId
            entityId = event.entityId
            oldValue = sanitize(event.oldValue)
            newValue = sanitize(event.newValue)
            this.reason = reason
            reauthenticated = event.reauthenticated
            correlationId = CorrelationId.current()
            ipAddress = ClientRequestInfo.currentIp()
        }
        return repository.save(log)
    }

    companion object {
        private val ACTION_FORMAT = Regex("^[A-Z][A-Z0-9_]{1,63}$")
        private val ENTITY_TYPE_FORMAT = Regex("^[a-z][a-z0-9_]{1,63}$")
        private val SENSITIVE_KEY = Regex("(?i).*(password|secret|token).*")

        /** Loại trường nhạy cảm và chuẩn hóa giá trị; map rỗng thành null. */
        fun sanitize(values: Map<String, Any?>?): Map<String, Any?>? {
            if (values == null) return null
            val result = LinkedHashMap<String, Any?>()
            values.forEach { (key, value) -> if (!SENSITIVE_KEY.matches(key)) result[key] = normalize(value) }
            return result.takeIf { it.isNotEmpty() }
        }

        /** Chuyển giá trị sang kiểu JSON đơn giản để định dạng lưu ổn định, không phụ thuộc module Jackson. */
        fun normalize(value: Any?): Any? = when (value) {
            null -> null
            is String, is Boolean, is Int, is Long, is Short, is BigDecimal, is BigInteger -> value
            is Enum<*> -> value.name
            is TemporalAccessor, is UUID -> value.toString()
            is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to normalize(v) }
            is Iterable<*> -> value.map { normalize(it) }
            is Array<*> -> value.map { normalize(it) }
            else -> value.toString()
        }
    }
}
