package com.qrorder.billing.domain

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
import org.hibernate.generator.EventType
import java.time.OffsetDateTime
import java.util.UUID

/** DEC-11 C: thanh toán thủ công; provider/providerTxnId chừa cho cổng online sau này. */
enum class PaymentMethod { CASH, BANK_TRANSFER, CARD_POS, OTHER }

/** Chỉ được chuyển PENDING -> SUCCESS/FAILED đúng một lần (BR-P10, BR-P13). */
enum class TransactionStatus { PENDING, SUCCESS, FAILED }

enum class TransactionType { PAYMENT, REFUND }

/**
 * Giao dịch THU tiền, bất biến (BR-P10). Idempotent theo requestId.
 * receiptNumber (YYYYMMDD-NNNN) do trigger cấp khi status = SUCCESS (BR-P12); ứng dụng không ghi.
 * Giới hạn TPS + TPP - TRS <= Total do trigger kiểm tra có khóa dòng Bill (PAYMENT_EXCEEDS_REMAINING).
 */
@Entity
@Table(name = "payment_transaction")
class PaymentTransaction(
    @Column(name = "bill_id", nullable = false, updatable = false)
    var billId: Long,

    @Column(name = "request_id", nullable = false, updatable = false)
    var requestId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 16, updatable = false)
    var method: PaymentMethod,

    @Column(name = "amount", nullable = false, updatable = false)
    var amount: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: TransactionStatus,

    @Column(name = "created_by", nullable = false, updatable = false)
    var createdBy: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Generated(event = [EventType.INSERT, EventType.UPDATE])
    @Column(name = "receipt_number", length = 13, insertable = false, updatable = false)
    var receiptNumber: String? = null
        protected set

    @Column(name = "provider", length = 50)
    var provider: String? = null

    @Column(name = "provider_txn_id", length = 100)
    var providerTxnId: String? = null

    @Column(name = "note", columnDefinition = "text")
    var note: String? = null

    @Column(name = "failure_reason", columnDefinition = "text")
    var failureReason: String? = null

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var createdAt: OffsetDateTime? = null
        protected set

    @Column(name = "confirmed_by")
    var confirmedBy: Long? = null

    @Column(name = "confirmed_at", columnDefinition = "timestamptz")
    var confirmedAt: OffsetDateTime? = null
}

/**
 * Giao dịch HOÀN tiền, chỉ phục vụ Void (BR-P13). Chỉ Manager (REFUND_RECORD) ghi; SUCCESS chỉ khi
 * tiền thực tế đã hoàn. receiptNumber (RF-YYYYMMDD-NNNN) do trigger cấp khi SUCCESS.
 * Giới hạn TRS + TRP <= TPS do trigger kiểm tra (REFUND_EXCEEDS_REFUNDABLE).
 */
@Entity
@Table(name = "refund_transaction")
class RefundTransaction(
    @Column(name = "bill_id", nullable = false, updatable = false)
    var billId: Long,

    @Column(name = "request_id", nullable = false, updatable = false)
    var requestId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 16, updatable = false)
    var method: PaymentMethod,

    @Column(name = "amount", nullable = false, updatable = false)
    var amount: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: TransactionStatus,

    @Column(name = "reason", nullable = false, updatable = false, columnDefinition = "text")
    var reason: String,

    @Column(name = "created_by", nullable = false, updatable = false)
    var createdBy: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Generated(event = [EventType.INSERT, EventType.UPDATE])
    @Column(name = "receipt_number", length = 16, insertable = false, updatable = false)
    var receiptNumber: String? = null
        protected set

    @Column(name = "failure_reason", columnDefinition = "text")
    var failureReason: String? = null

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var createdAt: OffsetDateTime? = null
        protected set

    @Column(name = "confirmed_by")
    var confirmedBy: Long? = null

    @Column(name = "confirmed_at", columnDefinition = "timestamptz")
    var confirmedAt: OffsetDateTime? = null
}

/**
 * Lịch sử trạng thái Payment/Refund (FR-PAY-04, FR-PAY-08). CHỈ ĐỌC: do trigger ghi; ứng dụng
 * không insert (mọi cột dữ liệu insertable = false).
 */
@Entity
@Immutable
@Table(name = "transaction_status_history")
class TransactionStatusHistory protected constructor() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 8, insertable = false, updatable = false)
    var transactionType: TransactionType = TransactionType.PAYMENT
        protected set

    @Column(name = "payment_transaction_id", insertable = false, updatable = false)
    var paymentTransactionId: Long? = null
        protected set

    @Column(name = "refund_transaction_id", insertable = false, updatable = false)
    var refundTransactionId: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 16, insertable = false, updatable = false)
    var fromStatus: TransactionStatus? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 16, insertable = false, updatable = false)
    var toStatus: TransactionStatus = TransactionStatus.PENDING
        protected set

    @Column(name = "changed_by", insertable = false, updatable = false)
    var changedBy: Long? = null
        protected set

    @Column(name = "changed_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var changedAt: OffsetDateTime? = null
        protected set

    @Column(name = "reason", insertable = false, updatable = false, columnDefinition = "text")
    var reason: String? = null
        protected set
}
