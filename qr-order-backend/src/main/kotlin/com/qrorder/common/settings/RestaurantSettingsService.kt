package com.qrorder.common.settings

import com.qrorder.common.api.ErrorDetail
import com.qrorder.common.audit.AuditActions
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEntityTypes
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

interface RestaurantSettingsRepository : JpaRepository<RestaurantSettings, Short>

/**
 * Quy tắc kiểm tra cấu hình (thuần, không phụ thuộc Spring để unit test).
 * Bean Validation trên DTO đã kiểm tra từng trường; ở đây kiểm tra lại các giới hạn tiền tệ (phòng khi
 * service được gọi trực tiếp) và ràng buộc chéo BR-P04. Trùng với CHECK của restaurant_settings.
 */
object RestaurantSettingsRules {
    const val RATE_SCALE = 4
    const val SERVICE_FEE_TAXABLE_REQUIRED =
        "Phải chốt phí phục vụ có chịu VAT hay không (serviceFeeTaxable) trước khi đặt phí phục vụ lớn hơn 0 (BR-P04)"

    fun validate(request: UpdateRestaurantSettingsRequest): List<ErrorDetail> {
        val details = mutableListOf<ErrorDetail>()
        request.vatRate?.let { if (!isValidRate(it)) details += ErrorDetail("vatRate", "VAT phải trong khoảng [0, 1) và tối đa 4 chữ số thập phân") }
        val fee = request.serviceFeeRate
        if (fee == null) {
            details += ErrorDetail("serviceFeeRate", "Phí phục vụ là bắt buộc")
        } else {
            if (!isValidRate(fee)) details += ErrorDetail("serviceFeeRate", "Phí phục vụ phải trong khoảng [0, 1) và tối đa 4 chữ số thập phân")
            if (fee.signum() > 0 && request.serviceFeeTaxable == null) {
                details += ErrorDetail("serviceFeeTaxable", SERVICE_FEE_TAXABLE_REQUIRED)
            }
        }
        return details
    }

    /** 0 <= rate < 1 và tối đa [RATE_SCALE] chữ số thập phân (NUMERIC(5,4)). */
    fun isValidRate(rate: BigDecimal): Boolean =
        rate.signum() >= 0 && rate < BigDecimal.ONE && rate.stripTrailingZeros().scale() <= RATE_SCALE

    fun normalizeRate(rate: BigDecimal): BigDecimal = rate.setScale(RATE_SCALE, RoundingMode.UNNECESSARY)
}

/**
 * Cấu hình nhà hàng (FR-CFG-01, BR-C01): đúng một dòng id = 1 do migration V1 tạo.
 * Mọi thay đổi ghi audit SETTINGS_CHANGED kèm giá trị cũ/mới của các trường bị đổi (BR-A05).
 */
@Service
class RestaurantSettingsService(
    private val repository: RestaurantSettingsRepository,
    private val auditService: AuditService,
) {
    /** Dùng cho module khác (Payment đọc VAT, Session đọc TTL...). */
    @Transactional(readOnly = true)
    fun current(): RestaurantSettings = load()

    @Transactional(readOnly = true)
    fun get(): RestaurantSettingsResponse = RestaurantSettingsResponse.of(load())

    @Transactional
    fun update(actorUserId: Long, request: UpdateRestaurantSettingsRequest): RestaurantSettingsResponse {
        val violations = RestaurantSettingsRules.validate(request)
        if (violations.isNotEmpty()) throw BusinessException(ErrorCode.VALIDATION_FAILED, details = violations)

        val settings = load()
        if (request.version != settings.version) throw BusinessException(ErrorCode.CONCURRENT_MODIFICATION)

        val before = snapshot(settings)
        settings.vatRate = request.vatRate?.let(RestaurantSettingsRules::normalizeRate)
        settings.serviceFeeRate = RestaurantSettingsRules.normalizeRate(requireNotNull(request.serviceFeeRate))
        settings.serviceFeeTaxable = request.serviceFeeTaxable
        settings.sessionIdleTimeoutMinutes = requireNotNull(request.sessionIdleTimeoutMinutes)
        settings.guestJwtTtlMinutes = requireNotNull(request.guestJwtTtlMinutes)
        settings.batchClaimTtlSeconds = requireNotNull(request.batchClaimTtlSeconds)
        settings.maxPendingBatchesPerSession = requireNotNull(request.maxPendingBatchesPerSession)
        settings.joinRateLimitPerMinute = requireNotNull(request.joinRateLimitPerMinute)
        settings.orderRateLimitPerMinute = requireNotNull(request.orderRateLimitPerMinute)
        settings.maxItemQuantity = requireNotNull(request.maxItemQuantity)
        settings.orderConfirmationMode = requireNotNull(request.orderConfirmationMode)
        settings.requireTablePin = requireNotNull(request.requireTablePin)
        settings.cleaningAutoRelease = requireNotNull(request.cleaningAutoRelease)
        val after = snapshot(settings)

        val changed = before.keys.filter { !sameValue(before[it], after[it]) }
        if (changed.isEmpty()) return RestaurantSettingsResponse.of(settings)

        settings.updatedBy = actorUserId
        // flush ngay: xung đột @Version (ghi đồng thời) và CHECK lộ ra trong transaction này.
        val saved = repository.saveAndFlush(settings)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = AuditActions.SETTINGS_CHANGED,
                entityType = AuditEntityTypes.RESTAURANT_SETTINGS,
                entityId = saved.id.toLong(),
                oldValue = before.filterKeys { it in changed },
                newValue = after.filterKeys { it in changed },
            ),
        )
        return RestaurantSettingsResponse.of(saved)
    }

    private fun load(): RestaurantSettings =
        repository.findById(RestaurantSettings.SINGLETON_ID).orElseThrow {
            // V1 luôn chèn dòng id = 1; thiếu dòng là lỗi triển khai, không phải lỗi người dùng.
            IllegalStateException("Thiếu dòng restaurant_settings id = 1 (migration V1)")
        }

    private fun snapshot(s: RestaurantSettings): Map<String, Any?> = linkedMapOf(
        "vatRate" to s.vatRate,
        "serviceFeeRate" to s.serviceFeeRate,
        "serviceFeeTaxable" to s.serviceFeeTaxable,
        "sessionIdleTimeoutMinutes" to s.sessionIdleTimeoutMinutes,
        "guestJwtTtlMinutes" to s.guestJwtTtlMinutes,
        "batchClaimTtlSeconds" to s.batchClaimTtlSeconds,
        "maxPendingBatchesPerSession" to s.maxPendingBatchesPerSession,
        "joinRateLimitPerMinute" to s.joinRateLimitPerMinute,
        "orderRateLimitPerMinute" to s.orderRateLimitPerMinute,
        "maxItemQuantity" to s.maxItemQuantity,
        "orderConfirmationMode" to s.orderConfirmationMode,
        "requireTablePin" to s.requireTablePin,
        "cleaningAutoRelease" to s.cleaningAutoRelease,
    )

    /** BigDecimal so sánh theo giá trị (0.08 == 0.0800), còn lại theo equals. */
    private fun sameValue(a: Any?, b: Any?): Boolean =
        if (a is BigDecimal && b is BigDecimal) a.compareTo(b) == 0 else a == b
}
