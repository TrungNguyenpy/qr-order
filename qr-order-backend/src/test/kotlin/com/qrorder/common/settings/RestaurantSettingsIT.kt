package com.qrorder.common.settings

import com.fasterxml.jackson.databind.ObjectMapper
import com.qrorder.auth.domain.UserRole
import com.qrorder.support.ApiTestClient
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.IntegrationTestBase
import com.qrorder.support.LoggedInUser
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal

/**
 * FR-CFG-01: GET/PUT /api/v1/settings - BR-P03, BR-P04, 0 <= VAT < 1, optimistic locking theo version,
 * audit SETTINGS_CHANGED (BR-A05, BR-C01). DbCleaner đặt lại cấu hình về seed dev (VAT 8%, version 1).
 */
@AutoConfigureMockMvc
class RestaurantSettingsIT : IntegrationTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val api by lazy { ApiTestClient(mvc, objectMapper, jdbc, passwordEncoder) }
    private lateinit var admin: LoggedInUser

    @BeforeEach
    fun setUp() {
        admin = api.loggedIn("admin1", UserRole.ADMIN)
    }

    /** Body PUT với giá trị seed; [overrides] thay một số trường. */
    private fun body(vararg overrides: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "vatRate" to BigDecimal("0.0800"),
            "serviceFeeRate" to BigDecimal.ZERO,
            "serviceFeeTaxable" to null,
            "sessionIdleTimeoutMinutes" to 180,
            "guestJwtTtlMinutes" to 15,
            "batchClaimTtlSeconds" to 60,
            "maxPendingBatchesPerSession" to 3,
            "joinRateLimitPerMinute" to 10,
            "orderRateLimitPerMinute" to 5,
            "maxItemQuantity" to 99,
            "orderConfirmationMode" to "FIRST_BATCH_ONLY",
            "requireTablePin" to false,
            "cleaningAutoRelease" to false,
            "version" to 1,
        ).apply { putAll(overrides) }

    private fun put(vararg overrides: Pair<String, Any?>) = api.put("/api/v1/settings", admin.accessToken, body(*overrides))
    private fun dbVersion(): Long = fx.count("SELECT version FROM restaurant_settings WHERE id = 1")

    @Test
    fun `ADMIN reads the settings including read-only fields`() {
        api.get("/api/v1/settings", admin.accessToken)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.vatRate").value(0.08))
            .andExpect(jsonPath("$.data.vatConfigured").value(true))
            .andExpect(jsonPath("$.data.serviceFeeRate").value(0.0))
            .andExpect(jsonPath("$.data.orderConfirmationMode").value("FIRST_BATCH_ONLY"))
            .andExpect(jsonPath("$.data.timezone").value("Asia/Ho_Chi_Minh"))
            .andExpect(jsonPath("$.data.moneyRoundingMode").value("HALF_UP"))
            .andExpect(jsonPath("$.data.pricesIncludeVat").value(true))
            .andExpect(jsonPath("$.data.version").value(1))
    }

    @Test
    fun `only ADMIN can read or change settings`() {
        listOf(UserRole.MANAGER, UserRole.STAFF, UserRole.KITCHEN).forEach { role ->
            val user = api.loggedIn("u_${role.name.lowercase()}", role)
            api.get("/api/v1/settings", user.accessToken).isError(403, "PERMISSION_DENIED")
            api.put("/api/v1/settings", user.accessToken, body()).isError(403, "PERMISSION_DENIED")
        }
        assertEquals(1L, dbVersion())
    }

    @Test
    fun `valid update bumps version, records updated_by and audits only the changed fields with old and new values`() {
        put("vatRate" to BigDecimal("0.1"), "maxItemQuantity" to 50, "orderConfirmationMode" to "ALL")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.vatRate").value(0.1))
            .andExpect(jsonPath("$.data.maxItemQuantity").value(50))
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.updatedBy").value(admin.id))

        assertEquals(0, BigDecimal("0.1000").compareTo(
            jdbc.queryForObject("SELECT vat_rate FROM restaurant_settings WHERE id = 1", BigDecimal::class.java),
        ))
        assertEquals(admin.id, fx.count("SELECT updated_by FROM restaurant_settings WHERE id = 1"))
        val audit = api.auditRows("SETTINGS_CHANGED").single()
        assertEquals("restaurant_settings", audit["entity_type"])
        assertEquals(1L, (audit["entity_id"] as Number).toLong())
        assertEquals(admin.id, (audit["actor_user_id"] as Number).toLong())
        val old = api.jsonOf(audit["old_value"])
        val new = api.jsonOf(audit["new_value"])
        assertEquals(setOf("vatRate", "maxItemQuantity", "orderConfirmationMode"), new.fieldNames().asSequence().toSet())
        assertEquals(0, BigDecimal("0.08").compareTo(old["vatRate"].decimalValue()))
        assertEquals(0, BigDecimal("0.1").compareTo(new["vatRate"].decimalValue()))
        assertEquals(99, old["maxItemQuantity"].asInt())
        assertEquals("ALL", new["orderConfirmationMode"].asText())
    }

    @Test
    fun `BR-P04 - service fee greater than 0 requires serviceFeeTaxable to be decided`() {
        put("serviceFeeRate" to BigDecimal("0.05"))
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[*].field", hasItem("serviceFeeTaxable")))
        assertEquals(1L, dbVersion())

        put("serviceFeeRate" to BigDecimal("0.05"), "serviceFeeTaxable" to true)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.serviceFeeRate").value(0.05))
            .andExpect(jsonPath("$.data.serviceFeeTaxable").value(true))
    }

    @Test
    fun `VAT must satisfy 0 le vat lt 1 with at most 4 decimals, null means not configured (BR-P03)`() {
        put("vatRate" to BigDecimal("1")).isError(400, "VALIDATION_FAILED")
        put("vatRate" to BigDecimal("1.5")).isError(400, "VALIDATION_FAILED")
        put("vatRate" to BigDecimal("-0.01")).isError(400, "VALIDATION_FAILED")
        put("vatRate" to BigDecimal("0.08125")).isError(400, "VALIDATION_FAILED")
        put("serviceFeeRate" to BigDecimal("1")).isError(400, "VALIDATION_FAILED")
        assertEquals(1L, dbVersion())

        put("vatRate" to BigDecimal("0.9999")).andExpect(status().isOk)
        put("vatRate" to BigDecimal.ZERO, "version" to 2).andExpect(status().isOk).andExpect(jsonPath("$.data.vatConfigured").value(true))
        put("vatRate" to null, "version" to 3).andExpect(status().isOk).andExpect(jsonPath("$.data.vatConfigured").value(false))
        assertNull(jdbc.queryForObject("SELECT vat_rate FROM restaurant_settings WHERE id = 1", BigDecimal::class.java))
    }

    @Test
    fun `positive integers and required fields are validated`() {
        put("maxItemQuantity" to 0).isError(400, "VALIDATION_FAILED")
        put("guestJwtTtlMinutes" to -5).isError(400, "VALIDATION_FAILED")
        put("orderConfirmationMode" to null).isError(400, "VALIDATION_FAILED")
        put("version" to null).isError(400, "VALIDATION_FAILED")
        api.postRaw("/api/v1/settings", admin.accessToken, "{}").isError(405, "METHOD_NOT_ALLOWED")
        assertEquals(1L, dbVersion())
    }

    @Test
    fun `stale version returns 409 CONCURRENT_MODIFICATION and changes nothing (optimistic locking)`() {
        put("maxItemQuantity" to 20).andExpect(status().isOk) // version 1 -> 2
        put("maxItemQuantity" to 30).isError(409, "CONCURRENT_MODIFICATION") // vẫn gửi version 1
        assertEquals(20L, fx.count("SELECT max_item_quantity FROM restaurant_settings WHERE id = 1"))
        assertEquals(2L, dbVersion())
        assertEquals(1, api.auditRows("SETTINGS_CHANGED").size)
    }

    @Test
    fun `bank account - saved, returned, audited, and cleared by sending all three empty`() {
        put("bankBin" to "970436", "bankAccountNo" to "0011001234567", "bankAccountName" to "NGUYEN VAN A")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.bankConfigured").value(true))
            .andExpect(jsonPath("$.data.bankBin").value("970436"))
            .andExpect(jsonPath("$.data.bankAccountNo").value("0011001234567"))
            .andExpect(jsonPath("$.data.bankAccountName").value("NGUYEN VAN A"))
            .andExpect(jsonPath("$.data.version").value(2))
        val audit = api.auditRows("SETTINGS_CHANGED").single()
        val new = api.jsonOf(audit["new_value"])
        assertEquals(setOf("bankBin", "bankAccountNo", "bankAccountName"), new.fieldNames().asSequence().toSet())

        // Gửi lại đúng giá trị: không đổi gì. Gửi rỗng cả ba: xóa cấu hình.
        put("bankBin" to "970436", "bankAccountNo" to "0011001234567", "bankAccountName" to "NGUYEN VAN A", "version" to 2)
            .andExpect(status().isOk).andExpect(jsonPath("$.data.version").value(2))
        put("bankBin" to "", "bankAccountNo" to null, "bankAccountName" to " ", "version" to 2)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.bankConfigured").value(false))
            .andExpect(jsonPath("$.data.bankBin").doesNotExist())
            .andExpect(jsonPath("$.data.version").value(3))
        assertNull(jdbc.queryForObject("SELECT bank_account_no FROM restaurant_settings WHERE id = 1", String::class.java))
    }

    @Test
    fun `bank account - partial or malformed input is rejected with field errors and changes nothing`() {
        put("bankBin" to "970436").isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[*].field", hasItem("bankAccountNo")))
        put("bankBin" to "9704", "bankAccountNo" to "0011001234567", "bankAccountName" to "A")
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[*].field", hasItem("bankBin")))
        put("bankBin" to "970436", "bankAccountNo" to "12ab56", "bankAccountName" to "A")
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[*].field", hasItem("bankAccountNo")))
        assertEquals(1L, dbVersion())
    }

    @Test
    fun `database CHECK also rejects a half-configured bank account (V4)`() {
        assertFailsWith<org.springframework.dao.DataIntegrityViolationException> {
            jdbc.update("UPDATE restaurant_settings SET bank_bin = '970436'")
        }
        assertFailsWith<org.springframework.dao.DataIntegrityViolationException> {
            jdbc.update("UPDATE restaurant_settings SET bank_bin = 'abc', bank_account_no = '123456', bank_account_name = 'A'")
        }
    }

    @Test
    fun `saving identical values is a no-op - no version bump and no audit`() {
        put().andExpect(status().isOk).andExpect(jsonPath("$.data.version").value(1))
        assertEquals(1L, dbVersion())
        assertTrue(api.auditRows("SETTINGS_CHANGED").isEmpty())
    }
}
