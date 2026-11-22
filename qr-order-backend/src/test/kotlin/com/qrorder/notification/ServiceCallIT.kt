package com.qrorder.notification

import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.BillingIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ServiceCall (FR-NOT-01..03, §9.8, DEC-08 B): khách gọi nhân viên / yêu cầu thanh toán, Staff gửi Manager hỗ trợ. */
class ServiceCallIT : BillingIntegrationTestBase() {

    private fun guestCall(token: String, type: String, note: String? = null) =
        api.post("/api/v1/guest/service-calls", token, mapOf("type" to type, "note" to note))

    @Test
    fun `guest calls staff - one open call per type, repeat returns the open call, realtime to staff and session`() {
        val guest = guestAt("T01")
        val first = data(guestCall(guest.token, "CALL_STAFF", "Cho thêm đá").andExpect(status().isCreated))
        assertEquals("OPEN", first["status"].asText())
        assertEquals(false, first["replayed"].asBoolean())

        val again = data(guestCall(guest.token, "CALL_STAFF").andExpect(status().isOk))
        assertEquals(first["id"].asLong(), again["id"].asLong())
        assertEquals(true, again["replayed"].asBoolean())
        // Thiết bị khác cùng phiên cũng thấy yêu cầu đang mở.
        assertEquals(first["id"].asLong(), data(guestCall(anotherDevice(guest).token, "CALL_STAFF").andExpect(status().isOk))["id"].asLong())
        guestCall(guest.token, "REQUEST_PAYMENT").andExpect(status().isCreated)
        assertEquals(2L, count("SELECT count(*) FROM service_call WHERE session_id = ?", guest.sessionId))

        val staffTopic = stomp.on("/topic/staff/orders").filter { it["type"].asText() == "SERVICE_CALL_CHANGED" }
        assertEquals(2, staffTopic.size)
        assertEquals("T01", staffTopic[0]["tableCode"].asText())
        assertTrue(stomp.on("/topic/session/${guest.sessionId}").any { it["type"].asText() == "SERVICE_CALL_CHANGED" })
        assertTrue(staffTopic.none { it.has("note") }, "thông báo không mang ghi chú")

        val mine = data(api.get("/api/v1/guest/service-calls", guest.token).andExpect(status().isOk))
        assertEquals(2, mine.size())
        assertTrue(mine[0]["acknowledgedBy"] == null, "khách không thấy thông tin nhân viên")
    }

    @Test
    fun `concurrent guest calls of the same type create exactly one row`() {
        val guest = guestAt("T01")
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val results = (1..4).map {
                pool.submit(Callable {
                    start.await()
                    guestCall(guest.token, "CALL_STAFF").andReturn().response.status
                })
            }
            start.countDown()
            val statuses = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, statuses.count { it == 201 }, statuses.toString())
            assertEquals(3, statuses.count { it == 200 }, statuses.toString())
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1L, count("SELECT count(*) FROM service_call WHERE session_id = ?", guest.sessionId))
    }

    @Test
    fun `staff acknowledges then resolves, a resolved call cannot change, a new call can be opened after`() {
        val guest = guestAt("T01")
        val id = data(guestCall(guest.token, "CALL_STAFF").andExpect(status().isCreated))["id"].asLong()

        val queue = data(api.get("/api/v1/service-calls", staff.accessToken).andExpect(status().isOk))
        assertEquals(id, queue.single()["id"].asLong())

        val ack = data(api.post("/api/v1/service-calls/$id/acknowledge", staff.accessToken, null).andExpect(status().isOk))
        assertEquals("ACKNOWLEDGED", ack["status"].asText())
        assertEquals(staff.id, ack["acknowledgedBy"].asLong())
        // Nhận lại: idempotent, không đổi người nhận.
        assertEquals(staff.id, data(api.post("/api/v1/service-calls/$id/acknowledge", manager.accessToken, null))["acknowledgedBy"].asLong())

        val done = data(api.post("/api/v1/service-calls/$id/resolve", staff.accessToken, mapOf("note" to "Đã mang đá")).andExpect(status().isOk))
        assertEquals("RESOLVED", done["status"].asText())
        assertEquals(false, done["autoResolved"].asBoolean())
        api.post("/api/v1/service-calls/$id/resolve", staff.accessToken, null).isError(409, "SERVICE_CALL_STATE_INVALID")
        api.post("/api/v1/service-calls/$id/acknowledge", staff.accessToken, null).isError(409, "SERVICE_CALL_STATE_INVALID")
        assertEquals(0, data(api.get("/api/v1/service-calls", staff.accessToken)).size())
        assertEquals(1, data(api.get("/api/v1/service-calls?status=RESOLVED", staff.accessToken)).size())

        guestCall(guest.token, "CALL_STAFF").andExpect(status().isCreated)
    }

    @Test
    fun `staff asks the manager for support - only MANAGER handles it, not pushed to the guest channel`() {
        val guest = guestAt("T01")
        val call = data(api.post("/api/v1/service-calls", staff.accessToken, mapOf("sessionId" to guest.sessionId, "note" to "Khách muốn giảm giá")).andExpect(status().isCreated))
        assertEquals("MANAGER_SUPPORT", call["type"].asText())
        assertEquals(staff.id, call["createdByUserId"].asLong())
        assertEquals(call["id"].asLong(), data(api.post("/api/v1/service-calls", staff.accessToken, mapOf("sessionId" to guest.sessionId)).andExpect(status().isOk))["id"].asLong())

        val id = call["id"].asLong()
        api.post("/api/v1/service-calls/$id/acknowledge", staff.accessToken, null).isError(403, "PERMISSION_DENIED")
        api.post("/api/v1/service-calls/$id/resolve", staff.accessToken, null).isError(403, "PERMISSION_DENIED")
        api.post("/api/v1/service-calls/$id/resolve", manager.accessToken, mapOf("note" to "Đã xử lý")).andExpect(status().isOk)

        assertTrue(stomp.on("/topic/staff/orders").any { it["callType"].asText() == "MANAGER_SUPPORT" })
        assertTrue(stomp.on("/topic/session/${guest.sessionId}").none { it["type"].asText() == "SERVICE_CALL_CHANGED" })
    }

    @Test
    fun `invalid requests - guest cannot send MANAGER_SUPPORT, missing type, admin and kitchen cannot read the queue`() {
        val guest = guestAt("T01")
        guestCall(guest.token, "MANAGER_SUPPORT").isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/guest/service-calls", guest.token, mapOf("note" to "x")).isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/guest/service-calls", staff.accessToken, mapOf("type" to "CALL_STAFF")).isError(403, "PERMISSION_DENIED")
        api.get("/api/v1/service-calls", admin.accessToken).isError(403, "PERMISSION_DENIED")
        api.get("/api/v1/service-calls", kitchen.accessToken).isError(403, "PERMISSION_DENIED")
        api.post("/api/v1/service-calls/999999/acknowledge", staff.accessToken, null).isError(404, "RESOURCE_NOT_FOUND")
    }

    @Test
    fun `open calls are auto RESOLVED when the session is force-closed`() {
        val guest = guestAt("T01")
        guestCall(guest.token, "CALL_STAFF").andExpect(status().isCreated)
        api.post("/api/v1/service-calls", staff.accessToken, mapOf("sessionId" to guest.sessionId)).andExpect(status().isCreated)
        api.post("/api/v1/sessions/${guest.sessionId}/force-close", manager.accessToken, mapOf("reason" to "Đóng ca")).andExpect(status().isOk)
        val rows = jdbc.queryForList("SELECT status, resolved_by FROM service_call WHERE session_id = ?", guest.sessionId)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it["status"] == "RESOLVED" })
        assertNull(rows[0]["resolved_by"])
        assertEquals(0, data(api.get("/api/v1/service-calls", staff.accessToken)).size())
        // Phiên đã đóng: Staff không tạo được yêu cầu cho phiên đó.
        api.post("/api/v1/service-calls", staff.accessToken, mapOf("sessionId" to guest.sessionId)).isError(409, "SESSION_FINAL_STATE")
    }
}
