package com.qrorder.order.domain

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

/** §9.3: VOIDED và WRITTEN_OFF là trạng thái cuối (P-2). */
enum class OrderStatus { OPEN, LOCKED, PAID, VOIDED, WRITTEN_OFF }

/** BR-P17: cờ needs_attention của Order (độc lập với cờ của phiên). */
enum class OrderAttentionReason { AWAITING_REPLACEMENT_BILL }

/**
 * Order của phiên bàn - DEC-02 A: mỗi table_session có ĐÚNG MỘT Order (uq_orders_session);
 * khách gửi nhiều lần thì tạo nhiều [OrderBatch] trong cùng Order, không tạo Order mới.
 *
 * Tên entity JPQL là "Orders" vì ORDER là từ khóa của JPQL.
 * Thất thu (shortfallAmount) lưu ở Order kể cả khi chưa có Bill (BR-P15).
 */
@Entity(name = "Orders")
@Table(name = "orders")
class Order(
    @Column(name = "session_id", nullable = false, updatable = false)
    var sessionId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: OrderStatus = OrderStatus.OPEN

    /** Trạng thái trước khi Manager yêu cầu Void (chỉ LOCKED | PAID) để rút Void trả về đúng (BR-P14). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status_before_void", length = 16)
    var statusBeforeVoid: OrderStatus? = null

    @Column(name = "needs_attention", nullable = false)
    var needsAttention: Boolean = false

    @Enumerated(EnumType.STRING)
    @Column(name = "attention_reason", length = 32)
    var attentionReason: OrderAttentionReason? = null

    @Column(name = "attention_set_at", columnDefinition = "timestamptz")
    var attentionSetAt: OffsetDateTime? = null

    @Column(name = "shortfall_amount")
    var shortfallAmount: Long? = null

    @Column(name = "writeoff_reason", columnDefinition = "text")
    var writeoffReason: String? = null

    @Column(name = "written_off_by")
    var writtenOffBy: Long? = null

    @Column(name = "written_off_at", columnDefinition = "timestamptz")
    var writtenOffAt: OffsetDateTime? = null

    @Column(name = "paid_at", columnDefinition = "timestamptz")
    var paidAt: OffsetDateTime? = null

    @Column(name = "voided_at", columnDefinition = "timestamptz")
    var voidedAt: OffsetDateTime? = null

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
