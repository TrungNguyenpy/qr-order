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

    @Test
    fun `normalizeRate keeps the value and uses scale 4`() {
        assertEquals(BigDecimal("0.0800"), RestaurantSettingsRules.normalizeRate(BigDecimal("0.08")))
        assertEquals(BigDecimal("0.1000"), RestaurantSettingsRules.normalizeRate(BigDecimal("0.10000")))
        assertTrue(RestaurantSettingsRules.isValidRate(BigDecimal("0.0000")))
        assertFalse(RestaurantSettingsRules.isValidRate(BigDecimal("0.00001")))
    }
}
