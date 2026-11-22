package com.qrorder.common.sequence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime

/** Ba dãy số chứng từ riêng (BR-P12). */
enum class SequenceType { BILL, PAYMENT_RECEIPT, REFUND_RECEIPT }

@Embeddable
data class DailySequenceId(
    @Enumerated(EnumType.STRING)
    @Column(name = "sequence_type", nullable = false, length = 20)
    val sequenceType: SequenceType = SequenceType.BILL,

    @Column(name = "business_date", nullable = false)
    val businessDate: LocalDate = LocalDate.EPOCH,
) : Serializable

/**
 * Dãy số chứng từ theo ngày Asia/Ho_Chi_Minh. CHỈ ĐỌC: số được cấp bởi hàm
 * `fn_next_document_number()` trong cùng transaction nghiệp vụ (trigger Bill/Payment/Refund tự gọi
 * khi mã để trống), ứng dụng không ghi bảng này.
 */
@Entity
@Immutable
@Table(name = "daily_sequence")
class DailySequence {
    @EmbeddedId
    var id: DailySequenceId = DailySequenceId()
        protected set

    @Column(name = "last_value", nullable = false, insertable = false, updatable = false)
    var lastValue: Int = 0
        protected set

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var updatedAt: OffsetDateTime? = null
        protected set
}
