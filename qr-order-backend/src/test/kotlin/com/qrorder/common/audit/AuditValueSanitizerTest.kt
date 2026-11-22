package com.qrorder.common.audit

import com.qrorder.auth.domain.UserRole
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Chuẩn hóa old_value/new_value của audit và loại trường nhạy cảm (quy tắc 7, NFR-04). */
class AuditValueSanitizerTest {

    @Test
    fun `sensitive keys are removed regardless of case`() {
        val result = AuditService.sanitize(
            mapOf("role" to "ADMIN", "password" to "x", "newPassword" to "y", "PASSWORD_HASH" to "z", "jwtSecret" to "s", "refreshToken" to "t"),
        )
        assertEquals(mapOf<String, Any?>("role" to "ADMIN"), result)
    }

    @Test
    fun `values are normalized to plain JSON types`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val result = AuditService.sanitize(
            mapOf(
                "role" to UserRole.MANAGER,
                "at" to Instant.parse("2026-11-20T01:02:03Z"),
                "rate" to BigDecimal("0.0800"),
                "count" to 3,
                "flag" to true,
                "id" to id,
                "list" to listOf(UserRole.ADMIN, UserRole.STAFF),
                "nothing" to null,
            ),
        )!!
        assertEquals("MANAGER", result["role"])
        assertEquals("2026-11-20T01:02:03Z", result["at"])
        assertEquals(BigDecimal("0.0800"), result["rate"])
        assertEquals(3, result["count"])
        assertEquals(true, result["flag"])
        assertEquals(id.toString(), result["id"])
        assertEquals(listOf("ADMIN", "STAFF"), result["list"])
        assertEquals(true, result.containsKey("nothing"))
    }

    @Test
    fun `null or empty maps become null`() {
        assertNull(AuditService.sanitize(null))
        assertNull(AuditService.sanitize(emptyMap()))
        assertNull(AuditService.sanitize(mapOf("password" to "x")))
    }
}
