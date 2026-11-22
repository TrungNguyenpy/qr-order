package com.qrorder.table

import com.qrorder.auth.domain.Permission
import com.qrorder.common.error.ErrorCode
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.TableIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FR-SES-02, FR-SES-03, BR-S06 (phạm vi GĐ3), §9.7: nhân viên mở phiên, danh sách phiên, force-close. */
class SessionLifecycleIT : TableIntegrationTestBase() {

    private fun forceClose(sessionId: Long, token: String, reason: String? = "Khách bỏ về", version: Long? = null) =
        api.post("/api/v1/sessions/$sessionId/force-close", token, mapOf("reason" to reason, "version" to version))

    @Test
    fun `staff opens a session for a guest without a phone - FR-SES-02`() {
        val t01 = tableId("T01")
        jdbc.update("UPDATE restaurant_settings SET require_table_pin = true")
        val opened = data(api.post("/api/v1/tables/$t01/sessions", staff.accessToken, null).andExpect(status().isCreated))
        val sessionId = opened["id"].asLong()
        assertEquals("STAFF", opened["openedByType"].asText())
        assertEquals(staff.id, opened["openedByUserId"].asLong())
        assertTrue(Regex("^[0-9]{4}$").matches(opened["tablePin"].asText()), "Nhân viên đọc PIN cho khách")
        assertEquals("OCCUPIED", tableStatus(t01))
        assertEquals(1, api.auditRows("SESSION_OPENED_BY_STAFF").size)
        api.post("/api/v1/tables/$t01/sessions", manager.accessToken, null).isError(409, "SESSION_ALREADY_ACTIVE")
        api.post("/api/v1/tables/${tableId("T02")}/sessions", admin.accessToken, null).isError(403, "PERMISSION_DENIED")
        api.post("/api/v1/tables/${tableId("T99")}/sessions", staff.accessToken, null).isError(409, "TABLE_INACTIVE")
        // Khách quét QR sau đó vào đúng phiên nhân viên đã mở.
        assertEquals(sessionId, joinOk("dev-qr-T01")["session"]["sessionId"].asLong())
    }

    @Test
    fun `list, filter and detail sessions - staff and manager, admin only with REPORT_VIEW`() {
        val s1 = joinOk("dev-qr-T01")["session"]["sessionId"].asLong()
        val s2 = joinOk("dev-qr-T02")["session"]["sessionId"].asLong()
        forceClose(s2, manager.accessToken).andExpect(status().isOk)
        val active = data(api.get("/api/v1/sessions", staff.accessToken).andExpect(status().isOk))
        assertEquals(listOf(s1), active["items"].map { it["id"].asLong() })
        val closed = data(api.get("/api/v1/sessions?status=FORCE_CLOSED&tableId=${tableId("T02")}", manager.accessToken))
        assertEquals(listOf(s2), closed["items"].map { it["id"].asLong() })
        val detail = data(api.get("/api/v1/sessions/$s1", staff.accessToken).andExpect(status().isOk))
        assertEquals("T01", detail["tableCode"].asText())
        assertEquals(1L, detail["deviceCount"].asLong())
        api.get("/api/v1/sessions", kitchen.accessToken).isError(403, "PERMISSION_DENIED")
        api.get("/api/v1/sessions", admin.accessToken).isError(403, "PERMISSION_DENIED")
        fx.grant(admin.id, Permission.REPORT_VIEW, admin.id)
        api.get("/api/v1/sessions/needs-attention", admin.accessToken).andExpect(status().isOk)
        api.get("/api/v1/sessions/999999", staff.accessToken).isError(404, "RESOURCE_NOT_FOUND")
    }

    @Test
    fun `force-close - permission, mandatory reason, version, FORCE_CLOSED + CLEANING, audit, events after commit`() {
        val joined = joinOk("dev-qr-T03")
        val sessionId = joined["session"]["sessionId"].asLong()
        val t03 = tableId("T03")
        forceClose(sessionId, staff.accessToken).isError(403, "PERMISSION_DENIED")
        forceClose(sessionId, admin.accessToken).isError(403, "PERMISSION_DENIED") // ADMIN không kế thừa (BR-A06)
        forceClose(sessionId, manager.accessToken, reason = "  ").isError(400, "VALIDATION_FAILED")
        forceClose(sessionId, manager.accessToken, version = 99).isError(409, "CONCURRENT_MODIFICATION")
        assertEquals("OPEN", sessionStatus(sessionId))
        assertTrue(stomp.on("/topic/session/$sessionId").none { it["change"].asText() == "CLOSED" }, "Không thông báo khi bị từ chối")

        val closed = data(forceClose(sessionId, manager.accessToken, reason = "Khách bỏ về").andExpect(status().isOk))
        assertEquals("FORCE_CLOSED", closed["status"].asText())
        assertEquals(manager.id, closed["closedBy"].asLong())
        assertEquals("Khách bỏ về", closed["closeReason"].asText())
        assertNotNull(closed["closedAt"].asText())
        assertEquals("CLEANING", tableStatus(t03))
        val audit = api.auditRows("SESSION_FORCE_CLOSED").single()
        assertEquals("Khách bỏ về", audit["reason"])
        assertEquals(manager.id, (audit["actor_user_id"] as Number).toLong())
        assertEquals("OPEN", api.jsonOf(audit["old_value"])["status"].asText())
        assertTrue(stomp.on("/topic/session/$sessionId").any { it["change"].asText() == "CLOSED" && it["status"].asText() == "FORCE_CLOSED" })
        assertFalse(stomp.on("/topic/session/$sessionId").any { it.has("closedBy") || it.has("closeReason") }, "Kênh phiên không lộ thông tin nhân viên")
        assertTrue(stomp.on("/topic/tables").any { it["tableId"].asLong() == t03 && it["status"].asText() == "CLEANING" })

        forceClose(sessionId, manager.accessToken).isError(409, "SESSION_FINAL_STATE")
        join("dev-qr-T03").isError(409, "TABLE_NOT_READY")
        // CHECK ck_table_session_force_close là hàng rào cuối: FORCE_CLOSED bắt buộc người + lý do.
        val other = joinOk("dev-qr-T04")["session"]["sessionId"].asLong()
        val check = expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            jdbc.update("UPDATE table_session SET status = 'FORCE_CLOSED', closed_at = now() WHERE id = ?", other)
        }
        assertEquals("ck_table_session_force_close", check.constraint)
    }

    @Test
    fun `permission holders can force-close - STAFF or ADMIN granted SESSION_FORCE_CLOSE`() {
        val s1 = joinOk("dev-qr-T05")["session"]["sessionId"].asLong()
        val s2 = joinOk("dev-qr-T06")["session"]["sessionId"].asLong()
        fx.grant(staff.id, Permission.SESSION_FORCE_CLOSE, admin.id)
        fx.grant(admin.id, Permission.SESSION_FORCE_CLOSE, admin.id)
        forceClose(s1, staff.accessToken).andExpect(jsonPath("$.data.status").value("FORCE_CLOSED"))
        forceClose(s2, admin.accessToken).andExpect(jsonPath("$.data.status").value("FORCE_CLOSED"))
    }

    @Test
    fun `needs_attention is cleared and cleaning_auto_release frees the table on force-close`() {
        jdbc.update("UPDATE restaurant_settings SET cleaning_auto_release = true")
        val sessionId = joinOk("dev-qr-T07")["session"]["sessionId"].asLong()
        jdbc.update(
            "UPDATE table_session SET needs_attention = true, attention_reason = 'IDLE_WITH_UNPAID_ORDER', attention_set_at = now() WHERE id = ?",
            sessionId,
        )
        forceClose(sessionId, manager.accessToken).andExpect(status().isOk)
        val row = jdbc.queryForMap("SELECT needs_attention, attention_reason, attention_set_at FROM table_session WHERE id = ?", sessionId)
        assertEquals(false, row["needs_attention"])
        assertNull(row["attention_reason"])
        assertEquals("AVAILABLE", tableStatus(tableId("T07")))
    }

    @Test
    fun `GD4 - force-close writes off an order that still has billable items (shortfall, audit), empty order becomes VOIDED`() {
        val sessionId = joinOk("dev-qr-T08")["session"]["sessionId"].asLong()
        val (orderId, itemId) = addUnpaidOrder(sessionId) // một món CONFIRMED 65.000
        forceClose(sessionId, manager.accessToken, reason = "Khách bỏ về").andExpect(status().isOk)
        assertEquals("FORCE_CLOSED", sessionStatus(sessionId))
        val written = jdbc.queryForMap(
            "SELECT status, shortfall_amount, writeoff_reason, written_off_by FROM orders WHERE id = ?", orderId,
        )
        assertEquals("WRITTEN_OFF", written["status"], "BR-S06 (iii): còn món tính tiền -> WRITTEN_OFF")
        assertEquals(65_000L, written["shortfall_amount"])
        assertEquals("Khách bỏ về", written["writeoff_reason"])
        assertEquals(manager.id, written["written_off_by"])
        assertEquals("CONFIRMED", jdbc.queryForObject("SELECT status FROM order_item WHERE id = ?", String::class.java, itemId))
        assertEquals(1, api.auditRows("ORDER_WRITTEN_OFF").size)

        val other = joinOk("dev-qr-T09")["session"]["sessionId"].asLong()
        val (emptyOrder, emptyItem) = addUnpaidOrder(other)
        cancelItem(emptyItem)
        forceClose(other, manager.accessToken).andExpect(status().isOk)
        val order = jdbc.queryForMap("SELECT status, voided_at, shortfall_amount FROM orders WHERE id = ?", emptyOrder)
        assertEquals("VOIDED", order["status"], "BR-S06 (iv): không có món tính tiền -> Order VOIDED")
        assertNotNull(order["voided_at"])
        assertNull(order["shortfall_amount"])
    }

    @Test
    fun `extension points - validators and listeners run in the transaction with full context, a rejection rolls back`() {
        val sessionId = joinOk("dev-qr-T09")["session"]["sessionId"].asLong()
        hooks.enabled = true
        hooks.rejectWith = ErrorCode.PENDING_PAYMENT_EXISTS
        forceClose(sessionId, manager.accessToken, reason = "Thử chặn").isError(409, "PENDING_PAYMENT_EXISTS")
        assertEquals(listOf("validate"), hooks.calls.map { it.phase })
        assertEquals("OPEN", sessionStatus(sessionId))

        hooks.calls.clear()
        hooks.rejectWith = null
        forceClose(sessionId, manager.accessToken, reason = "  Có lý do  ").andExpect(status().isOk)
        assertEquals(listOf("validate", "listener"), hooks.calls.map { it.phase })
        val ctx = hooks.calls.last().context
        assertEquals(sessionId, ctx.sessionId)
        assertEquals(tableId("T09"), ctx.tableId)
        assertEquals(manager.id, ctx.actorUserId)
        assertEquals("Có lý do", ctx.reason)
        assertEquals("OPEN", ctx.previousStatus.name)
        assertTrue(hooks.calls.all { it.insideTransaction })
    }
}
