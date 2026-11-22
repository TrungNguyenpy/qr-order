package com.qrorder.common.settings

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.OffsetDateTime

/** BR-S04, DEC-07 A. */
enum class OrderConfirmationMode { FIRST_BATCH_ONLY, ALL, NONE }

/**
 * Cấu hình nghiệp vụ, đúng 1 dòng id = 1 do V1 tạo sẵn (BR-C01). Ứng dụng chỉ đọc và cập nhật,
 * không tạo dòng mới. Đổi cấu hình phải ghi audit_log (BR-A05) - thực hiện ở Giai đoạn 1.
 *
 * vatRate = null nghĩa là chưa cấu hình: chặn tạo Bill với VAT_NOT_CONFIGURED (BR-P03).
 */
@Entity
@Table(name = "restaurant_settings")
class RestaurantSettings {
    @Id
    @Column(name = "id", nullable = false)
    var id: Short = SINGLETON_ID
        protected set

    @Column(name = "timezone", nullable = false, length = 64)
    var timezone: String = "Asia/Ho_Chi_Minh"
        protected set

    @Column(name = "money_rounding_mode", nullable = false, length = 16)
    var moneyRoundingMode: String = "HALF_UP"
        protected set

    @Column(name = "vat_rate", precision = 5, scale = 4)
    var vatRate: BigDecimal? = null

    @Column(name = "prices_include_vat", nullable = false)
    var pricesIncludeVat: Boolean = true
        protected set

    @Column(name = "service_fee_rate", nullable = false, precision = 5, scale = 4)
    var serviceFeeRate: BigDecimal = BigDecimal.ZERO

    @Column(name = "service_fee_taxable")
    var serviceFeeTaxable: Boolean? = null

    @Column(name = "session_idle_timeout_minutes", nullable = false)
    var sessionIdleTimeoutMinutes: Int = 180

    @Column(name = "guest_jwt_ttl_minutes", nullable = false)
    var guestJwtTtlMinutes: Int = 15

    @Column(name = "batch_claim_ttl_seconds", nullable = false)
    var batchClaimTtlSeconds: Int = 60

    @Column(name = "max_pending_batches_per_session", nullable = false)
    var maxPendingBatchesPerSession: Int = 3

    @Column(name = "join_rate_limit_per_minute", nullable = false)
    var joinRateLimitPerMinute: Int = 10

    @Column(name = "order_rate_limit_per_minute", nullable = false)
    var orderRateLimitPerMinute: Int = 5

    @Column(name = "max_item_quantity", nullable = false)
    var maxItemQuantity: Int = 99

    @Enumerated(EnumType.STRING)
    @Column(name = "order_confirmation_mode", nullable = false, length = 20)
    var orderConfirmationMode: OrderConfirmationMode = OrderConfirmationMode.FIRST_BATCH_ONLY

    @Column(name = "require_table_pin", nullable = false)
    var requireTablePin: Boolean = false

    @Column(name = "cleaning_auto_release", nullable = false)
    var cleaningAutoRelease: Boolean = false

    @Column(name = "updated_by")
    var updatedBy: Long? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    var updatedAt: OffsetDateTime? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    companion object {
        const val SINGLETON_ID: Short = 1
    }
}
