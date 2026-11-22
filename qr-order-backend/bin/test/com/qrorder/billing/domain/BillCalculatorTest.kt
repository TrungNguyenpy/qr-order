package com.qrorder.billing.domain

import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * BR-P01, BR-P02, BR-P07, BR-P11: số liệu Bill tính ở server phải khớp từng CHECK tiền của bảng `bill`
 * (ck_bill_net, ck_bill_service_fee, ck_bill_total, ck_bill_vat_amount) - làm tròn HALF_UP như round(numeric).
 */
class BillCalculatorTest {

    private val vat8 = BigDecimal("0.0800")
    private val zero = BigDecimal("0.0000")

    private fun pct(v: String) = DiscountInput(DiscountType.PERCENT, BigDecimal(v))
    private fun fixed(v: Long) = DiscountInput(DiscountType.FIXED_AMOUNT, BigDecimal(v))

    private fun invalid(block: () -> Unit) {
        val ex = assertFailsWith<BusinessException> { block() }
        assertEquals(ErrorCode.INVALID_DISCOUNT, ex.errorCode)
    }

    @Test
    fun `no discount, no service fee - VAT is extracted from the VAT-inclusive total, not added`() {
        val a = BillCalculator.calculate(159_000, 0, zero, null, vat8)
        assertEquals(159_000L, a.net)
        assertEquals(0L, a.serviceFee)
        assertEquals(159_000L, a.total, "BR-P02 - Total = Net + ServiceFee, VAT không cộng thêm")
        assertEquals(11_778L, a.vatAmount, "159000 x 0,08 / 1,08 = 11777,78 -> 11778")
    }

    @Test
    fun `percent discount is computed on the subtotal before the service fee with HALF_UP`() {
        assertEquals(15_900L, BillCalculator.discountAmount(159_000, pct("10")))
        assertEquals(1_250L, BillCalculator.discountAmount(10_001, pct("12.5")), "1250,125 -> 1250")
        assertEquals(5L, BillCalculator.discountAmount(10, pct("50")))
        assertEquals(3L, BillCalculator.discountAmount(5, pct("50")), "2,5 -> 3 (HALF_UP)")
        assertEquals(10_001L, BillCalculator.discountAmount(10_001, pct("100")))
    }

    @Test
    fun `half-up boundaries of percent discount`() {
        assertEquals(1L, BillCalculator.discountAmount(199, pct("0.75")), "199 x 0,75 / 100 = 1,4925 -> 1")
        assertEquals(2L, BillCalculator.discountAmount(200, pct("0.75")), "1,5 -> 2")
    }

    @Test
    fun `fixed amount discount is taken as is and may equal the subtotal`() {
        assertEquals(20_000L, BillCalculator.discountAmount(159_000, fixed(20_000)))
        assertEquals(159_000L, BillCalculator.discountAmount(159_000, fixed(159_000)))
        assertEquals(0L, BillCalculator.discountAmount(159_000, null))
        assertEquals(0L, BillCalculator.discountAmount(159_000, DiscountInput(DiscountType.NONE, null)))
    }

    @Test
    fun `invalid discounts are rejected with INVALID_DISCOUNT - BR-P07`() {
        invalid { BillCalculator.discountAmount(159_000, fixed(159_001)) }
        invalid { BillCalculator.discountAmount(159_000, fixed(0)) }
        invalid { BillCalculator.discountAmount(159_000, DiscountInput(DiscountType.FIXED_AMOUNT, BigDecimal("100.5"))) }
        invalid { BillCalculator.discountAmount(159_000, pct("0")) }
        invalid { BillCalculator.discountAmount(159_000, pct("100.01")) }
        invalid { BillCalculator.discountAmount(159_000, pct("-5")) }
        invalid { BillCalculator.discountAmount(159_000, pct("10.125")) }
        invalid { BillCalculator.discountAmount(159_000, DiscountInput(DiscountType.PERCENT, null)) }
        invalid { BillCalculator.discountAmount(10, pct("1")) } // 0,1 -> 0 đồng
        invalid { BillCalculator.discountAmount(0, pct("50")) } // Bill chỉ có món giá 0
    }

    @Test
    fun `service fee rounds HALF_UP on Net and VAT includes it only when taxable`() {
        val rate = BigDecimal("0.1000")
        val notTaxable = BillCalculator.calculate(159_000, 15_900, rate, false, vat8)
        assertEquals(143_100L, notTaxable.net)
        assertEquals(14_310L, notTaxable.serviceFee)
        assertEquals(157_410L, notTaxable.total)
        assertEquals(10_600L, notTaxable.vatAmount, "TaxBase = Net = 143100")

        val taxable = BillCalculator.calculate(159_000, 15_900, rate, true, vat8)
        assertEquals(157_410L, taxable.total)
        assertEquals(11_660L, taxable.vatAmount, "TaxBase = Net + ServiceFee = 157410")

        val half = BillCalculator.calculate(25, 0, BigDecimal("0.1000"), false, zero)
        assertEquals(3L, half.serviceFee, "2,5 -> 3")
        assertEquals(28L, half.total)
        assertEquals(0L, half.vatAmount, "VAT 0% hợp lệ (BR-P03)")
    }

    @Test
    fun `VAT rounding matches the exact rational HALF_UP for every small tax base`() {
        val rates = listOf("0.0800", "0.1000", "0.0500", "0.0750", "0.0333", "0.9999").map(::BigDecimal)
        for (rate in rates) {
            val u = rate.unscaledValue()
            val d = BigInteger.TEN.pow(rate.scale()).add(u)
            for (base in 0L..5_000L) {
                val n = BigInteger.valueOf(base).multiply(u)
                // Làm tròn tham chiếu bằng BigDecimal độ chính xác cao.
                val expected = BigDecimal(n).divide(BigDecimal(d), 30, RoundingMode.HALF_UP)
                    .setScale(0, RoundingMode.HALF_UP).toLong()
                assertEquals(expected, BillCalculator.vatIncluded(base, rate), "base=$base rate=$rate")
            }
        }
    }

    @Test
    fun `total zero bill - free items only`() {
        val a = BillCalculator.calculate(0, 0, zero, null, vat8)
        assertEquals(0L, a.total)
        assertEquals(0L, a.vatAmount)
        assertEquals(PaymentStatus.PAID, PaymentStatusRule.of(0, a.total), "BR-P11 - net 0 >= Total 0")
    }

    @Test
    fun `payment status follows net collected - BR-P11`() {
        assertEquals(PaymentStatus.UNPAID, PaymentStatusRule.of(0, 100))
        assertEquals(PaymentStatus.PARTIALLY_PAID, PaymentStatusRule.of(1, 100))
        assertEquals(PaymentStatus.PARTIALLY_PAID, PaymentStatusRule.of(99, 100))
        assertEquals(PaymentStatus.PAID, PaymentStatusRule.of(100, 100))
    }

    @Test
    fun `calculate rejects a discount larger than the subtotal`() {
        assertFailsWith<IllegalArgumentException> { BillCalculator.calculate(100, 101, zero, null, vat8) }
    }
}
