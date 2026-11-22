package com.qrorder.common.audit

import com.qrorder.common.domain.AuditActorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Generated
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.generator.EventType
import org.hibernate.type.SqlTypes
import java.net.InetAddress
import java.time.OffsetDateTime

/**
 * Nhật ký thao tác nhạy cảm, chỉ thêm (BR-A05). Trigger chặn UPDATE/DELETE; entity đánh dấu
 * @Immutable để Hibernate không bao giờ sinh câu UPDATE. Danh mục action: DATABASE_DESIGN.md §6.
 */
@Entity
@Immutable
@Table(name = "audit_log")
class AuditLog(
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 8)
    var actorType: AuditActorType,

    @Column(name = "action", nullable = false, length = 64)
    var action: String,

    @Column(name = "entity_type", nullable = false, length = 64)
    var entityType: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Generated(event = [EventType.INSERT])
    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var occurredAt: OffsetDateTime? = null
        protected set

    @Column(name = "actor_user_id")
    var actorUserId: Long? = null

    @Column(name = "entity_id")
    var entityId: Long? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    var oldValue: Map<String, Any?>? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    var newValue: Map<String, Any?>? = null

    @Column(name = "reason", columnDefinition = "text")
    var reason: String? = null

    @Column(name = "reauthenticated", nullable = false)
    var reauthenticated: Boolean = false

    @Column(name = "correlation_id", length = 64)
    var correlationId: String? = null

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address", columnDefinition = "inet")
    var ipAddress: InetAddress? = null
}
