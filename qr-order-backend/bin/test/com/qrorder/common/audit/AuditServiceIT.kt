package com.qrorder.common.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.UserRole
import com.qrorder.common.web.CorrelationId
import com.qrorder.support.ApiTestClient
import com.qrorder.support.IntegrationTestBase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.IllegalTransactionStateException
import java.time.OffsetDateTime
import org.junit.jupiter.api.BeforeEach
import org.springframework.web.context.request.RequestContextHolder
/** AuditService dùng chung (BR-A05, NFR-10): ghi đủ 11 trường vào audit_log, bắt buộc chạy trong transaction. */
@AutoConfigureMockMvc
class AuditServiceIT : IntegrationTestBase() {

    @Autowired lateinit var auditService: AuditService
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val api by lazy { ApiTestClient(mvc, objectMapper, jdbc, passwordEncoder) }

   @BeforeEach
    fun clearRequestContext() {
        RequestContextHolder.resetRequestAttributes()
    }

    @AfterEach
    fun clearMdc() {
        MDC.remove(CorrelationId.MDC_KEY)
        RequestContextHolder.resetRequestAttributes()
    }

    @Test
    fun `record writes actor, action, entity, old and new values, reason, reauthenticated and correlation id`() {
        val adminId = api.createUser("admin1", UserRole.ADMIN)
        MDC.put(CorrelationId.MDC_KEY, "corr-audit-01")

        tx.executeWithoutResult {
            auditService.record(
                AuditActor.User(adminId),
                AuditEvent(
                    action = AuditActions.USER_ROLE_CHANGED,
                    entityType = AuditEntityTypes.USERS,
                    entityId = 42,
                    oldValue = mapOf("role" to UserRole.MANAGER, "at" to OffsetDateTime.parse("2026-11-20T01:02:03Z")),
                    newValue = mapOf("role" to UserRole.ADMIN, "password" to "Bimat123", "refreshToken" to "abc"),
                    reason = "  Bổ nhiệm  ",
                    reauthenticated = true,
                ),
            )
        }

        val row = api.auditRows(AuditActions.USER_ROLE_CHANGED).single()
        assertEquals("USER", row["actor_type"])
        assertEquals(adminId, (row["actor_user_id"] as Number).toLong())
        assertEquals("users", row["entity_type"])
        assertEquals(42L, (row["entity_id"] as Number).toLong())
        assertEquals("MANAGER", api.jsonOf(row["old_value"])["role"].asText())
        assertTrue(api.jsonOf(row["old_value"])["at"].asText().startsWith("2026-11-20T01:02:03"))
        assertEquals("ADMIN", api.jsonOf(row["new_value"])["role"].asText())
        assertFalse((row["new_value"] as String).contains("Bimat123"), "Trường nhạy cảm bị loại khỏi new_value")
        assertFalse((row["new_value"] as String).contains("refreshToken"))
        assertEquals("Bổ nhiệm", row["reason"])
        assertEquals(true, row["reauthenticated"])
        assertEquals("corr-audit-01", row["correlation_id"])
        assertNull(row["ip_address"], "Không có request HTTP thì không có IP")
    }

    @Test
    fun `record must run inside the caller's transaction (Propagation MANDATORY)`() {
        assertFailsWith<IllegalTransactionStateException> {
            auditService.record(AuditActor.System, AuditEvent("SETTINGS_CHANGED", "restaurant_settings", 1))
        }
        assertEquals(0L, fx.count("SELECT count(*) FROM audit_log"))
    }

    @Test
    fun `audit rolls back together with the business change`() {
        assertFailsWith<IllegalStateException> {
            tx.executeWithoutResult {
                auditService.record(AuditActor.System, AuditEvent("SETTINGS_CHANGED", "restaurant_settings", 1))
                error("lỗi nghiệp vụ giả lập")
            }
        }
        assertEquals(0L, fx.count("SELECT count(*) FROM audit_log"))
    }

    @Test
    fun `programming errors are caught before hitting database CHECKs`() {
        // Mỗi trường hợp một transaction riêng: exception thoát khỏi proxy @Transactional đánh dấu rollback-only.
        fun rejected(actor: AuditActor, event: AuditEvent) =
            assertFailsWith<IllegalArgumentException> { tx.executeWithoutResult { auditService.record(actor, event) } }

        rejected(AuditActor.System, AuditEvent(AuditActions.PERMISSION_GRANTED, "user_permission", 1)) // ck_audit_log_reason_required
        rejected(AuditActor.System, AuditEvent("SETTINGS_CHANGED", "restaurant_settings", 1, reauthenticated = true)) // ck_audit_log_reauth_actor
        rejected(AuditActor.User(1), AuditEvent(AuditActions.USER_ROLE_CHANGED, "users", 1, reason = "x")) // ck_audit_log_role_change
        rejected(AuditActor.System, AuditEvent("bad-action", "users", 1)) // ck_audit_log_action
        rejected(AuditActor.System, AuditEvent("SETTINGS_CHANGED", "Users", 1)) // ck_audit_log_entity_type
        assertEquals(0L, fx.count("SELECT count(*) FROM audit_log"))
    }

    @Test
    fun `audit through HTTP records the request correlation id and client IP`() {
        val admin = api.loggedIn("admin1", UserRole.ADMIN)
        val staff = api.createUser("nv_1", UserRole.STAFF)

        api.perform(
            MockMvcRequestBuilders.post("/api/v1/users/$staff/permissions").with { it.remoteAddr = "203.0.113.7"; it },
            admin.accessToken,
            mapOf("permission" to Permission.REPORT_VIEW.name, "reason" to "Xem báo cáo"),
            correlationId = "http-audit-77",
        ).andExpect(status().isCreated)

        val row = api.auditRows(AuditActions.PERMISSION_GRANTED).single()
        assertEquals("http-audit-77", row["correlation_id"])
        assertEquals("203.0.113.7", row["ip_address"])
        assertEquals(admin.id, (row["actor_user_id"] as Number).toLong())
    }
}
