package com.qrorder.table.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Generated
import org.hibernate.generator.EventType
import java.time.OffsetDateTime

/** Nhiều thiết bị chung một phiên (FR-SES-01, §7.1). deviceId do client sinh. */
@Entity
@Table(name = "session_device")
class SessionDevice(
    @Column(name = "session_id", nullable = false, updatable = false)
    var sessionId: Long,

    @Column(name = "device_id", nullable = false, length = 64, updatable = false)
    var deviceId: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Generated(event = [EventType.INSERT])
    @Column(name = "joined_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var joinedAt: OffsetDateTime? = null
        protected set

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamptz")
    var lastSeenAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "pin_verified_at", columnDefinition = "timestamptz")
    var pinVerifiedAt: OffsetDateTime? = null
}
