package com.qrorder.common.settings

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** BR-P03, BR-P04 và giới hạn tỷ lệ NUMERIC(5,4) của restaurant_settings. */
class RestaurantSettingsRulesTest {

    private fun request(vat: String?, fee: String?, taxable: Boolean?) = UpdateRestaurantSettingsRequest(
        vatRate = vat?.let(::BigDecimal),
        serviceFeeRate = fee?.let(::BigDecimal),
        serviceFeeTaxable = taxable,
    )

    private fun fields(vat: String?, fee: String?, taxable: Boolean?) =
        RestaurantSettingsRules.validate(request(vat, fee, taxable)).map { it.field }

    @Test
    fun `BR-P04 - service fee above 0 requires serviceFeeTaxable`() {
        assertEquals(listOf("serviceFeeTaxable"), fields("0.08", "0.05", null))
        assertEquals(emptyList(), fields("0.08", "0.05", true))
        assertEquals(emptyList(), fields("0.08", "0.05", false))
        assertEquals(emptyList(), fields("0.08", "0", null))
        assertEquals(emptyList(), fields("0.08", "0.0000", null))
    }

    @Test
    fun `VAT is optional (null = not configured) but must be in 0 to below 1 with at most 4 decimals`() {
        assertEquals(emptyList(), fields(null, "0", null))
        assertEquals(emptyList(), fields("0", "0", null))
        assertEquals(emptyList(), fields("0.9999", "0", null))
        assertEquals(emptyList(), fields("0.10000", "0", null)) // số 0 thừa ở cuối không tính
        assertEquals(listOf("vatRate"), fields("1", "0", null))
        assertEquals(listOf("vatRate"), fields("-0.0001", "0", null))
        assertEquals(listOf("vatRate"), fields("0.08125", "0", null))
    }

    @Test
    fun `service fee is required and bounded`() {
        assertEquals(listOf("serviceFeeRate"), fields("0.08", null, null))
        assertEquals(listOf("serviceFeeRate"), fields("0.08", "1", true))
        assertEquals(listOf("serviceFeeRate"), fields("0.08", "-0.01", true))
    }

    private fun bankFields(bin: String?, account: String?, name: String?) =
        RestaurantSettingsRules.validate(
            UpdateRestaurantSettingsRequest(
                vatRate = BigDecimal("0.08"), serviceFeeRate = BigDecimal.ZERO,
                bankBin = bin, bankAccountNo = account, bankAccountName = name,
            ),
        ).map { it.field }

    @Test
    fun `bank account - all three or none (V4), blank counts as empty`() {
        assertEquals(emptyList(), bankFields(null, null, null))
        assertEquals(emptyList(), bankFields("  ", "", " "))
        assertEquals(emptyList(), bankFields("970436", "0011001234567", "NGUYEN VAN A"))
        assertEquals(listOf("bankAccountNo", "bankAccountName"), bankFields("970436", null, null))
        assertEquals(listOf("bankBin"), bankFields(null, "0011001234567", "NGUYEN VAN A"))
    }

    @Test
    fun `bank account - BIN has 6 digits and account number 6 to 19 digits`() {
        assertEquals(listOf("bankBin"), bankFields("97043", "0011001234567", "A"))
        assertEquals(listOf("bankBin"), bankFields("97043a", "0011001234567", "A"))
        assertEquals(listOf("bankAccountNo"), bankFields("970436", "12345", "A"))
        assertEquals(listOf("bankAccountNo"), bankFields("970436", "00110012345678901234", "A"))
        assertEquals(listOf("bankAccountNo"), bankFields("970436", "0011-0012", "A"))
        assertEquals(emptyList(), bankFields(" 970436 ", " 123456 ", " A  B "))
    }

    @Test
    fun `bank account input is trimmed and collapses inner whitespace in the name`() {
        val input = BankAccountInput.of(
            UpdateRestaurantSettingsRequest(bankBin = " 970436 ", bankAccountNo = "123456", bankAccountName = "  NGUYEN   VAN A "),
        )
        assertEquals(BankAccountInput("970436", "123456", "NGUYEN VAN A"), input)
    }

    @Test
    fun `normalizeRate keeps the value and uses scale 4`() {
        assertEquals(BigDecimal("0.0800"), RestaurantSettingsRules.normalizeRate(BigDecimal("0.08")))
        assertEquals(BigDecimal("0.1000"), RestaurantSettingsRules.normalizeRate(BigDecimal("0.10000")))
        assertTrue(RestaurantSettingsRules.isValidRate(BigDecimal("0.0000")))
        assertFalse(RestaurantSettingsRules.isValidRate(BigDecimal("0.00001")))
    }
}
