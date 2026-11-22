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

enum class CancelRequestStatus { PENDING, APPROVED, DENIED }

/**
 * Yêu cầu hủy món đã vào chế biến (PREPARING/READY), Manager có ITEM_CANCEL_APPROVE duyệt (BR-O06).
 * Tối đa 1 yêu cầu PENDING mỗi món.
 */
@Entity
@Table(name = "item_cancel_request")
class ItemCancelRequest(
    @Column(name = "order_item_id", nullable = false, updatable = false)
    var orderItemId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "item_status_at_request", nullable = false, length = 16, updatable = false)
    var itemStatusAtRequest: OrderItemStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_by_type", nullable = false, length = 8, updatable = false)
    var requestedByType: RequesterType,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: CancelRequestStatus = CancelRequestStatus.PENDING

    @Column(name = "requested_by_device_id", length = 64, updatable = false)
    var requestedByDeviceId: String? = null

    @Column(name = "requested_by_user_id", updatable = false)
    var requestedByUserId: Long? = null

    @Column(name = "request_reason", columnDefinition = "text")
    var requestReason: String? = null

    @Generated(event = [EventType.INSERT])
    @Column(name = "requested_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var requestedAt: OffsetDateTime? = null
        protected set

    @Column(name = "decided_by")
    var decidedBy: Long? = null

    @Column(name = "decided_at", columnDefinition = "timestamptz")
    var decidedAt: OffsetDateTime? = null

    @Column(name = "decision_reason", columnDefinition = "text")
    var decisionReason: String? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    var updatedAt: OffsetDateTime? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set
}
