package com.qrorder.table.domain

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

/** DEC-04 B: 4 trạng thái; ngừng dùng bằng cờ active, không phải trạng thái. */
enum class TableStatus { AVAILABLE, OCCUPIED, PAYMENT_PENDING, CLEANING }

/**
 * Bàn. status là cột denormalized, phải cập nhật cùng transaction với phiên theo ma trận §9.7 (BR-S08).
 * Chỉ đặt active = false khi bàn AVAILABLE (CHECK ck_dining_table_inactive_only_available).
 */
@Entity
@Table(name = "dining_table")
class DiningTable(
    @Column(name = "code", nullable = false, length = 20)
    var code: String,

    @Column(name = "display_name", nullable = false, length = 50)
    var displayName: String,

    @Column(name = "capacity", nullable = false)
    var capacity: Short,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Column(name = "area", length = 50)
    var area: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: TableStatus = TableStatus.AVAILABLE

    @Column(name = "is_active", nullable = false)
    var active: Boolean = true

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0

    @Column(name = "status_changed_at", nullable = false, columnDefinition = "timestamptz")
    var statusChangedAt: OffsetDateTime = OffsetDateTime.now()

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var createdAt: OffsetDateTime? = null
        protected set

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    var updatedAt: OffsetDateTime? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set
}
