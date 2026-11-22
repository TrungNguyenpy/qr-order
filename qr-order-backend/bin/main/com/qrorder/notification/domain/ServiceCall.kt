package com.qrorder.notification.domain

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

/** CALL_STAFF, REQUEST_PAYMENT do khách tạo; MANAGER_SUPPORT do Staff tạo (§9.8). */
enum class ServiceCallType { CALL_STAFF, REQUEST_PAYMENT, MANAGER_SUPPORT }

enum class ServiceCallStatus { OPEN, ACKNOWLEDGED, RESOLVED }

/** Yêu cầu hỗ trợ (FR-NOT-03). Tối đa 1 yêu cầu OPEN/ACKNOWLEDGED mỗi (phiên, loại). */
@Entity
@Table(name = "service_call")
class ServiceCall(
    @Column(name = "session_id", nullable = false, updatable = false)
    var sessionId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    var type: ServiceCallType,

    @Enumerated(EnumType.STRING)
    @Column(name = "created_by_type", nullable = false, length = 8, updatable = false)
    var createdByType: RequesterType,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: ServiceCallStatus = ServiceCallStatus.OPEN

    @Column(name = "created_by_device_id", length = 64, updatable = false)
    var createdByDeviceId: String? = null

    @Column(name = "created_by_user_id", updatable = false)
    var createdByUserId: Long? = null

    @Column(name = "note", columnDefinition = "text")
    var note: String? = null

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var createdAt: OffsetDateTime? = null
        protected set

    @Column(name = "acknowledged_by")
    var acknowledgedBy: Long? = null

    @Column(name = "acknowledged_at", columnDefinition = "timestamptz")
    var acknowledgedAt: OffsetDateTime? = null

    @Column(name = "resolved_by")
    var resolvedBy: Long? = null

    @Column(name = "resolved_at", columnDefinition = "timestamptz")
    var resolvedAt: OffsetDateTime? = null

    @Column(name = "resolution_note", columnDefinition = "text")
    var resolutionNote: String? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    var updatedAt: OffsetDateTime? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set
}
