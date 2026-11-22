package com.qrorder.table.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Generated
import org.hibernate.generator.EventType
import java.time.OffsetDateTime

/** BR-S05: xoay vòng = ROTATED + tạo dòng mới; thu hồi = REVOKED. */
enum class QrCodeStatus { ACTIVE, ROTATED, REVOKED }

/**
 * QR bàn. Chỉ lưu SHA-256 hex của qrToken (>= 128 bit ngẫu nhiên), không lưu token gốc (BR-S05).
 * Tra cứu khi join: token_hash = ? AND status = 'ACTIVE'. Tối đa 1 QR ACTIVE mỗi bàn.
 */
@Entity
@Table(name = "qr_code")
class QrCode(
    @Column(name = "table_id", nullable = false, updatable = false)
    var tableId: Long,

    @Column(name = "token_hash", nullable = false, updatable = false, columnDefinition = "bpchar(64)")
    var tokenHash: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: QrCodeStatus = QrCodeStatus.ACTIVE

    @Column(name = "created_by", updatable = false)
    var createdBy: Long? = null

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var createdAt: OffsetDateTime? = null
        protected set

    @Column(name = "deactivated_by")
    var deactivatedBy: Long? = null

    @Column(name = "deactivated_at", columnDefinition = "timestamptz")
    var deactivatedAt: OffsetDateTime? = null

    @Column(name = "deactivation_reason", columnDefinition = "text")
    var deactivationReason: String? = null
}
