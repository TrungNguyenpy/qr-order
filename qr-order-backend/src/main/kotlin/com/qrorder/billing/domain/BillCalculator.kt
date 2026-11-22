package com.qrorder.billing.domain

import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Hình thức giảm giá (BR-P07, chốt GĐ5a): theo phần trăm hoặc số tiền cố định, tính trên Subtotal (tổng món tính tiền
 * CONFIRMED, PREPARING, READY, SERVED, trước phí phục vụ). [NONE] dùng để gỡ giảm giá khi phát hành lại Bill.
 *
 * Ghi chú tên gọi: yêu cầu GĐ5a gọi giá trị gốc là "Net (trước phí phục vụ)"; schema V1 gọi đó là `subtotal_amount`
 * và `net_amount = subtotal_amount - discount_amount` (CHECK ck_bill_net). Mã nguồn dùng tên của schema.
 */
enum class DiscountType { PERCENT, FIXED_AMOUNT, NONE }

/** Giảm giá do Manager nhập: [value] là phần trăm (PERCENT, tối đa 2 chữ số thập phân) hoặc số VND (FIXED_AMOUNT). */
data class DiscountInput(val type: DiscountType, val value: BigDecimal?)

/** Số liệu snapshot của một Bill, khớp từng CHECK tiền của bảng `bill` (BR-P02). */
data class BillAmounts(
    val subtotal: Long,
    val discount: Long,
    val net: Long,
    val serviceFeeRate: BigDecimal,
    val serviceFeeTaxable: Boolean?,
    val serviceFee: Long,
    val total: Long,
    val vatRate: BigDecimal,
    val vatAmount: Long,
)

/** BR-P11: suy trạng thái thanh toán từ tiền thu ròng (TPS - TRS) so với Total. */
object PaymentStatusRule {
    fun of(netCollected: Long, total: Long): PaymentStatus = when {
        netCollected >= total -> PaymentStatus.PAID
        netCollected > 0 -> PaymentStatus.PARTIALLY_PAID
        else -> PaymentStatus.UNPAID
    }
}

/**
 * Tính tiền ở server (BR-P01, BR-P02, FR-PAY-02). Tiền là Long VND, làm tròn HALF_UP (số không âm) đúng như
 * `round(numeric)` của PostgreSQL trong các CHECK:
 *
 * - Subtotal = tổng line_total của món tính tiền; Discount theo [DiscountInput] (HALF_UP); Net = Subtotal - Discount.
 * - ServiceFee = round(Net x s) (ck_bill_service_fee); Total = Net + ServiceFee (ck_bill_total; VAT đã nằm trong giá).
 * - VAT = round(TaxBase x r / (1 + r)), TaxBase = Net (+ ServiceFee nếu service_fee_taxable = true) (ck_bill_vat_amount).
 *
 * VAT được tính bằng phân số nguyên chính xác (r = unscaled / 10^scale) nên không có sai số dấu phẩy động. PostgreSQL
 * chia numeric với tối thiểu 16 chữ số có nghĩa: hai cách cho cùng kết quả với mọi số tiền dưới 10^11 VND (phần lẻ
 * của phép chia luôn cách 0,5 ít nhất 1/(2 x 20000)). Lệch ở số lớn hơn sẽ bị CHECK chặn (lỗi 500, không ghi sai).
 */
object BillCalculator {
    /** Phần trăm giảm tối đa 2 chữ số thập phân, trong (0, 100]. */
    private const val PERCENT_SCALE = 2
    private val HUNDRED = BigDecimal(100)

    /** Số tiền giảm (VND) cho [subtotal]; null hoặc [DiscountType.NONE] = 0. Ném INVALID_DISCOUNT khi không hợp lệ. */
    fun discountAmount(subtotal: Long, input: DiscountInput?): Long {
        require(subtotal >= 0) { "subtotal âm" }
        if (input == null || input.type == DiscountType.NONE) return 0
        val value = input.value ?: throw invalid("Thiếu giá trị giảm giá")
        val amount = when (input.type) {
            DiscountType.PERCENT -> {
                if (value <= BigDecimal.ZERO || value > HUNDRED) throw invalid("Phần trăm giảm giá phải lớn hơn 0 và không quá 100")
                if (value.stripTrailingZeros().scale() > PERCENT_SCALE) throw invalid("Phần trăm giảm giá tối đa 2 chữ số thập phân")
                BigDecimal(subtotal).multiply(value).divide(HUNDRED, 0, RoundingMode.HALF_UP).longValueExact()
            }
            DiscountType.FIXED_AMOUNT -> {
                val normalized = value.stripTrailingZeros()
                if (normalized.scale() > 0) throw invalid("Số tiền giảm phải là số nguyên VND")
                if (normalized <= BigDecimal.ZERO) throw invalid("Số tiền giảm phải lớn hơn 0")
                if (normalized > BigDecimal(subtotal)) throw invalid("Số tiền giảm không được vượt tổng tiền món (BR-P07)")
                normalized.longValueExact()
            }
            DiscountType.NONE -> 0L
        }
        if (amount <= 0) throw invalid("Giảm giá tính ra 0 đồng, không cần áp dụng")
        return amount
    }

    fun calculate(
        subtotal: Long,
        discount: Long,
        serviceFeeRate: BigDecimal,
        serviceFeeTaxable: Boolean?,
        vatRate: BigDecimal,
    ): BillAmounts {
        require(subtotal >= 0 && discount >= 0 && discount <= subtotal) { "Giảm giá phải trong [0, subtotal]" }
        require(serviceFeeRate >= BigDecimal.ZERO && serviceFeeRate < BigDecimal.ONE) { "service_fee_rate ngoài [0, 1)" }
        require(vatRate >= BigDecimal.ZERO && vatRate < BigDecimal.ONE) { "vat_rate ngoài [0, 1)" }
        val net = subtotal - discount
        val serviceFee = BigDecimal(net).multiply(serviceFeeRate).setScale(0, RoundingMode.HALF_UP).longValueExact()
        val total = net + serviceFee
        val taxBase = net + if (serviceFeeTaxable == true) serviceFee else 0L
        val vat = vatIncluded(taxBase, vatRate)
        return BillAmounts(subtotal, discount, net, serviceFeeRate, serviceFeeTaxable, serviceFee, total, vatRate, vat)
    }

    /** round(taxBase x r / (1 + r)) HALF_UP, tính bằng phân số nguyên: r = u / 10^s => taxBase x u / (10^s + u). */
    fun vatIncluded(taxBase: Long, vatRate: BigDecimal): Long {
        require(taxBase >= 0) { "taxBase âm" }
        val rate = if (vatRate.scale() < 0) vatRate.setScale(0) else vatRate
        val unscaled = rate.unscaledValue()
        val pow = BigInteger.TEN.pow(rate.scale())
        val numerator = BigInteger.valueOf(taxBase).multiply(unscaled)
        val denominator = pow.add(unscaled)
        return roundHalfUp(numerator, denominator)
    }

    /** floor((2n + d) / 2d) = HALF_UP của n/d với n >= 0, d > 0. */
    fun roundHalfUp(numerator: BigInteger, denominator: BigInteger): Long {
        require(numerator.signum() >= 0 && denominator.signum() > 0)
        val two = BigInteger.TWO
        return numerator.multiply(two).add(denominator).divide(denominator.multiply(two)).longValueExact()
    }

    private fun invalid(message: String) = BusinessException(ErrorCode.INVALID_DISCOUNT, message)
}
