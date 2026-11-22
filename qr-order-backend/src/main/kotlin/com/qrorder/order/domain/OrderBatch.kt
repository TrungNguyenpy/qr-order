package com.qrorder.order.domain

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
import java.util.UUID

/** §9.2 */
enum class BatchStatus { PENDING, ACCEPTED, PARTIALLY_ACCEPTED, REJECTED, CANCELLED, COMPLETED }

/**
 * Một lần khách (hoặc Staff order hộ) gửi món = một đợt trong Order của phiên (BR-O04).
 *
 * - Idempotent: UNIQUE (session_id, client_request_id); gửi lại cùng clientRequestId trả kết quả cũ.
 * - sessionId lưu lại ở đợt để có unique trên; FK kép (order_id, session_id) bảo đảm khớp Order.
 * - Claim (BR-O10): UPDATE có điều kiện theo claimed_by/claimed_at và TTL (DATABASE_DESIGN.md §5.3 (8)).
 */
@Entity
@Table(name = "order_batch")
class OrderBatch(
    @Column(name = "order_id", nullable = false, updatable = false)
    var orderId: Long,

    @Column(name = "session_id", nullable = false, updatable = false)
    var sessionId: Long,

    @Column(name = "seq", nullable = false, updatable = false)
    var seq: Int,

    @Column(name = "client_request_id", nullable = false, updatable = false)
    var clientRequestId: UUID,

    @Column(name = "requires_confirmation", nullable = false, updatable = false)
    var requiresConfirmation: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(name = "submitted_by_type", nullable = false, length = 8, updatable = false)
    var submittedByType: RequesterType,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: BatchStatus = BatchStatus.PENDING

    @Column(name = "device_id", length = 64, updatable = false)
    var deviceId: String? = null

    @Column(name = "submitted_by_user_id", updatable = false)
    var submittedByUserId: Long? = null

    @Generated(event = [EventType.INSERT])
    @Column(name = "submitted_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var submittedAt: OffsetDateTime? = null
        protected set

    @Column(name = "claimed_by")
    var claimedBy: Long? = null

    @Column(name = "claimed_at", columnDefinition = "timestamptz")
    var claimedAt: OffsetDateTime? = null

    @Column(name = "decided_by")
    var decidedBy: Long? = null

    @Column(name = "decided_at", columnDefinition = "timestamptz")
    var decidedAt: OffsetDateTime? = null

    @Column(name = "reason_code", length = 50)
    var reasonCode: String? = null

    @Column(name = "reason_note", columnDefinition = "text")
    var reasonNote: String? = null

    @Column(name = "completed_at", columnDefinition = "timestamptz")
    var completedAt: OffsetDateTime? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    var updatedAt: OffsetDateTime? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set
}
