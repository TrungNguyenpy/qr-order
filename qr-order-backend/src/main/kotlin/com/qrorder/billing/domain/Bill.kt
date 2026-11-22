package com.qrorder.billing.domain

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
import java.math.BigDecimal
import java.time.OffsetDateTime

/** §9.4, §9.10: VOIDED và WRITTEN_OFF là trạng thái cuối. */
enum class BillStatus { ACTIVE, VOID_PENDING, VOIDED, WRITTEN_OFF }

/** BR-P11: suy ra từ net_collected = TPS - TRS so với total_amount. */
enum class PaymentStatus { UNPAID, PARTIALLY_PAID, PAID }

/** Ba đường dẫn tới VOIDED (BR-P08 a, BR-P09, BR-P15/P18). */
enum class VoidType { SESSION_REOPEN, MANAGER_VOID, FORCE_CLOSE_NO_CHARGE }

/**
 * Hóa đơn tạm tính nội bộ. Số liệu tiền là snapshot bất biến (BR-P06): các cột snapshot
 * updatable = false và trigger `fn_bill_guard` cấm sửa; sửa sai bằng Void + Bill thay thế.
 *
 * - billNumber (BILL-YYYYMMDD-NNNN) do trigger cấp khi insert (fn_next_document_number), ứng dụng
 *   không ghi cột này và đọc lại giá trị sau insert (@Generated).
 * - paymentStatus phải được đặt đúng trong CÙNG transaction với giao dịch thu/hoàn tiền; trigger
 *   DEFERRABLE `trg_bill_invariants` kiểm tra lúc COMMIT (BILL_PAYMENT_STATUS_MISMATCH).
 */
@Entity
@Table(name = "bill")
class Bill(
    @Column(name = "order_id", nullable = false, updatable = false)
    var orderId: Long,

    @Column(name = "subtotal_amount", nullable = false, updatable = false)
    var subtotalAmount: Long,

    @Column(name = "discount_amount", nullable = false, updatable = false)
    var discountAmount: Long,

    @Column(name = "net_amount", nullable = false, updatable = false)
    var netAmount: Long,

    @Column(name = "service_fee_rate", nullable = false, precision = 5, scale = 4, updatable = false)
    var serviceFeeRate: BigDecimal,

    @Column(name = "service_fee_amount", nullable = false, updatable = false)
    var serviceFeeAmount: Long,

    @Column(name = "total_amount", nullable = false, updatable = false)
    var totalAmount: Long,

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 4, updatable = false)
    var vatRate: BigDecimal,

    @Column(name = "vat_amount", nullable = false, updatable = false)
    var vatAmount: Long,

    @Column(name = "created_by", nullable = false, updatable = false)
    var createdBy: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Generated(event = [EventType.INSERT])
    @Column(name = "bill_number", nullable = false, length = 18, insertable = false, updatable = false)
    var billNumber: String? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: BillStatus = BillStatus.ACTIVE

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 16)
    var paymentStatus: PaymentStatus = PaymentStatus.UNPAID

    @Column(name = "discount_reason", updatable = false, columnDefinition = "text")
    var discountReason: String? = null

    @Column(name = "discounted_by", updatable = false)
    var discountedBy: Long? = null

    @Column(name = "service_fee_taxable", updatable = false)
    var serviceFeeTaxable: Boolean? = null

    @Column(name = "replaces_bill_id", updatable = false)
    var replacesBillId: Long? = null

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var createdAt: OffsetDateTime? = null
        protected set

    @Column(name = "paid_at", columnDefinition = "timestamptz")
    var paidAt: OffsetDateTime? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "void_type", length = 32)
    var voidType: VoidType? = null

    @Column(name = "void_requested_by")
    var voidRequestedBy: Long? = null

    @Column(name = "void_requested_at", columnDefinition = "timestamptz")
    var voidRequestedAt: OffsetDateTime? = null

    @Column(name = "void_reason", columnDefinition = "text")
    var voidReason: String? = null

    @Column(name = "voided_by")
    var voidedBy: Long? = null

    @Column(name = "voided_at", columnDefinition = "timestamptz")
    var voidedAt: OffsetDateTime? = null

    @Column(name = "void_withdrawn_by")
    var voidWithdrawnBy: Long? = null

    @Column(name = "void_withdrawn_at", columnDefinition = "timestamptz")
    var voidWithdrawnAt: OffsetDateTime? = null

    @Column(name = "void_withdraw_reason", columnDefinition = "text")
    var voidWithdrawReason: String? = null

    @Column(name = "written_off_at", columnDefinition = "timestamptz")
    var writtenOffAt: OffsetDateTime? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    var updatedAt: OffsetDateTime? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set
}
