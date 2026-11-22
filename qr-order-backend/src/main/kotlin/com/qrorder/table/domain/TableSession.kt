package com.qrorder.table.domain

import com.qrorder.common.domain.RequesterType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.Generated
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.generator.EventType
import java.time.OffsetDateTime

/** §9.6: CLOSED, EXPIRED, FORCE_CLOSED là trạng thái cuối. */
enum class SessionStatus {
    OPEN, BILLING, CLOSED, EXPIRED, FORCE_CLOSED;

    val isActive: Boolean get() = this == OPEN || this == BILLING
}

/** BR-S07: cờ needs_attention của phiên chỉ có một lý do. */
enum class SessionAttentionReason { IDLE_WITH_UNPAID_ORDER }

/**
 * Phiên bàn (Table Session). Tối đa 1 phiên OPEN/BILLING mỗi bàn (partial unique index);
 * phiên mới luôn là dòng mới. Máy trạng thái được trigger `trg_table_session_guard` bảo vệ.
 */
@Entity
@Table(name = "table_session")
class TableSession(
    @Column(name = "table_id", nullable = false, updatable = false)
    var tableId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "opened_by_type", nullable = false, length = 8, updatable = false)
    var openedByType: RequesterType,

    @Column(name = "opened_by_user_id", updatable = false)
    var openedByUserId: Long? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: SessionStatus = SessionStatus.OPEN

    /** DB đặt (DEFAULT now()); trigger cấm sửa. */
    @Generated(event = [EventType.INSERT])
    @Column(name = "opened_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var openedAt: OffsetDateTime? = null
        protected set

    @Column(name = "last_activity_at", nullable = false, columnDefinition = "timestamptz")
    var lastActivityAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "verified_at", columnDefinition = "timestamptz")
    var verifiedAt: OffsetDateTime? = null

    @Column(name = "table_pin", columnDefinition = "bpchar(4)")
    var tablePin: String? = null

    @Column(name = "needs_attention", nullable = false)
    var needsAttention: Boolean = false

    @Enumerated(EnumType.STRING)
    @Column(name = "attention_reason", length = 32)
    var attentionReason: SessionAttentionReason? = null

    @Column(name = "attention_set_at", columnDefinition = "timestamptz")
    var attentionSetAt: OffsetDateTime? = null

    @Column(name = "billing_started_at", columnDefinition = "timestamptz")
    var billingStartedAt: OffsetDateTime? = null

    @Column(name = "closed_at", columnDefinition = "timestamptz")
    var closedAt: OffsetDateTime? = null

    @Column(name = "closed_by")
    var closedBy: Long? = null

    @Column(name = "close_reason", columnDefinition = "text")
    var closeReason: String? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    var updatedAt: OffsetDateTime? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set
}
