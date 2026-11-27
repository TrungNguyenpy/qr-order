package com.qrorder.common.settings

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * Cấu hình nhà hàng trả về Admin Dashboard (FR-CFG-01). Tỷ lệ dạng thập phân: 0.08 = 8%.
 * timezone, moneyRoundingMode, pricesIncludeVat chỉ đọc (khóa bằng CHECK ở giai đoạn 1, BR-P01, DEC-10 A).
 */
data class RestaurantSettingsResponse(
    val vatRate: BigDecimal?,
    val vatConfigured: Boolean,
    val serviceFeeRate: BigDecimal,
    val serviceFeeTaxable: Boolean?,
    val sessionIdleTimeoutMinutes: Int,
    val guestJwtTtlMinutes: Int,
    val batchClaimTtlSeconds: Int,
    val maxPendingBatchesPerSession: Int,
    val joinRateLimitPerMinute: Int,
    val orderRateLimitPerMinute: Int,
    val maxItemQuantity: Int,
    val orderConfirmationMode: OrderConfirmationMode,
    val requireTablePin: Boolean,
    val cleaningAutoRelease: Boolean,
    /** Tài khoản nhận tiền VietQR; null = chưa cấu hình (không hiển thị mã QR). */
    val bankBin: String?,
    val bankAccountNo: String?,
    val bankAccountName: String?,
    val bankConfigured: Boolean,
    val timezone: String,
    val moneyRoundingMode: String,
    val pricesIncludeVat: Boolean,
    val updatedBy: Long?,
    val updatedAt: OffsetDateTime?,
    val version: Long,
) {
    companion object {
        fun of(s: RestaurantSettings) = RestaurantSettingsResponse(
            vatRate = s.vatRate,
            vatConfigured = s.vatRate != null,
            serviceFeeRate = s.serviceFeeRate,
            serviceFeeTaxable = s.serviceFeeTaxable,
            sessionIdleTimeoutMinutes = s.sessionIdleTimeoutMinutes,
            guestJwtTtlMinutes = s.guestJwtTtlMinutes,
            batchClaimTtlSeconds = s.batchClaimTtlSeconds,
            maxPendingBatchesPerSession = s.maxPendingBatchesPerSession,
            joinRateLimitPerMinute = s.joinRateLimitPerMinute,
            orderRateLimitPerMinute = s.orderRateLimitPerMinute,
            maxItemQuantity = s.maxItemQuantity,
            orderConfirmationMode = s.orderConfirmationMode,
            requireTablePin = s.requireTablePin,
            cleaningAutoRelease = s.cleaningAutoRelease,
            bankBin = s.bankBin,
            bankAccountNo = s.bankAccountNo,
            bankAccountName = s.bankAccountName,
            bankConfigured = s.bankBin != null,
            timezone = s.timezone,
            moneyRoundingMode = s.moneyRoundingMode,
            pricesIncludeVat = s.pricesIncludeVat,
            updatedBy = s.updatedBy,
            updatedAt = s.updatedAt,
            version = s.version,
        )
    }
}

/**
 * PUT /api/v1/settings: thay thế toàn bộ các trường được phép sửa. [version] là version client đã đọc
 * (optimistic locking, NFR-05); lệch thì trả 409 CONCURRENT_MODIFICATION.
 *
 * vatRate = null nghĩa là "chưa cấu hình" (BR-P03, chặn tạo Bill). Ràng buộc chéo BR-P04
 * (serviceFeeRate > 0 cần serviceFeeTaxable) kiểm tra ở [RestaurantSettingsService].
 */
data class UpdateRestaurantSettingsRequest(
    @field:DecimalMin(value = "0", inclusive = true, message = "VAT phải từ 0 trở lên")
    @field:DecimalMax(value = "1", inclusive = false, message = "VAT phải nhỏ hơn 1 (100%)")
    @field:Digits(integer = 1, fraction = 4, message = "VAT tối đa 4 chữ số thập phân")
    val vatRate: BigDecimal? = null,

    @field:NotNull(message = "Phí phục vụ là bắt buộc")
    @field:DecimalMin(value = "0", inclusive = true, message = "Phí phục vụ phải từ 0 trở lên")
    @field:DecimalMax(value = "1", inclusive = false, message = "Phí phục vụ phải nhỏ hơn 1 (100%)")
    @field:Digits(integer = 1, fraction = 4, message = "Phí phục vụ tối đa 4 chữ số thập phân")
    val serviceFeeRate: BigDecimal? = null,

    val serviceFeeTaxable: Boolean? = null,

    @field:NotNull(message = "Bắt buộc") @field:Positive(message = "Phải lớn hơn 0")
    val sessionIdleTimeoutMinutes: Int? = null,
    @field:NotNull(message = "Bắt buộc") @field:Positive(message = "Phải lớn hơn 0")
    val guestJwtTtlMinutes: Int? = null,
    @field:NotNull(message = "Bắt buộc") @field:Positive(message = "Phải lớn hơn 0")
    val batchClaimTtlSeconds: Int? = null,
    @field:NotNull(message = "Bắt buộc") @field:Positive(message = "Phải lớn hơn 0")
    val maxPendingBatchesPerSession: Int? = null,
    @field:NotNull(message = "Bắt buộc") @field:Positive(message = "Phải lớn hơn 0")
    val joinRateLimitPerMinute: Int? = null,
    @field:NotNull(message = "Bắt buộc") @field:Positive(message = "Phải lớn hơn 0")
    val orderRateLimitPerMinute: Int? = null,
    @field:NotNull(message = "Bắt buộc") @field:Positive(message = "Phải lớn hơn 0")
    val maxItemQuantity: Int? = null,

    @field:NotNull(message = "Bắt buộc")
    val orderConfirmationMode: OrderConfirmationMode? = null,
    @field:NotNull(message = "Bắt buộc")
    val requireTablePin: Boolean? = null,
    @field:NotNull(message = "Bắt buộc")
    val cleaningAutoRelease: Boolean? = null,

    /**
     * Tài khoản nhận tiền VietQR. Gửi cả ba hoặc để trống cả ba (xóa cấu hình). Chuỗi rỗng coi như null. Định dạng kiểm tra ở RestaurantSettingsRules (sau khi trim).
     * Vì PUT thay thế toàn bộ, client phải gửi lại giá trị hiện có nếu không muốn xóa.
     */
    val bankBin: String? = null,
    val bankAccountNo: String? = null,
    val bankAccountName: String? = null,

    @field:NotNull(message = "Thiếu version (optimistic locking)")
    @field:PositiveOrZero(message = "version không hợp lệ")
    val version: Long? = null,
)
