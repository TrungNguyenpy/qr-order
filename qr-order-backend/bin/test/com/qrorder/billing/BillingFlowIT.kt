package com.qrorder.billing

import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.UserRole
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.BillingIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tính tiền (FR-PAY-01..03, FR-PAY-05, BR-P01..P07, BR-O11, DEC-08 B, O5) và phát hành lại Bill khi đổi giảm giá (BE-29).
 */
class BillingFlowIT : BillingIntegrationTestBase() {

    @Test
    fun `create bill - snapshot amounts, BILL number, order LOCKED, session BILLING, table PAYMENT_PENDING`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val bill = createBillOk(orderId)

        assertTrue(bill["billNumber"].asText().matches(Regex("^BILL-${businessDate()}-\\d{4}$")), bill["billNumber"].asText())
        assertEquals("ACTIVE", bill["status"].asText())
        assertEquals("UNPAID", bill["paymentStatus"].asText())
        assertEquals(159_000L, bill["subtotalAmount"].asLong(), "2 Phở nhỏ 130000 + Cà phê 29000")
        assertEquals(0L, bill["discountAmount"].asLong())
        assertEquals(159_000L, bill["netAmount"].asLong())
        assertEquals(0L, bill["serviceFeeAmount"].asLong())
        assertEquals(159_000L, bill["totalAmount"].asLong(), "giá đã gồm VAT, không cộng thêm (BR-P02)")
        assertEquals(vat(159_000), bill["vatAmount"].asLong())
        assertEquals(11_778L, bill["vatAmount"].asLong())
        assertEquals(159_000L, bill["remainingAmount"].asLong())
        assertEquals(2, bill["lines"].size())
        assertEquals("LOCKED", bill["orderStatus"].asText())
        assertEquals("BILLING", bill["sessionStatus"].asText())

        assertEquals("LOCKED", orderStatusOf(guest.sessionId))
        assertEquals("BILLING", sessionStatus(guest.sessionId))
        assertEquals("PAYMENT_PENDING", tableStatus(tableId("T01")))
        assertNotNull(jdbc.queryForObject("SELECT billing_started_at FROM table_session WHERE id = ?", java.time.OffsetDateTime::class.java, guest.sessionId))
        assertEquals(cashier.id, long(billRow(bill["id"].asLong()), "created_by"))
        assertEquals(1, api.auditRows("BILL_CREATED").size)

        // Sự kiện sau COMMIT: phiếu tạm tính + phiên BILLING tới khách và nhân viên.
        val sessionTopic = stomp.on("/topic/session/${guest.sessionId}")
        assertTrue(sessionTopic.any { it["type"].asText() == "BILL_CHANGED" && it["change"].asText() == "BILL_CREATED" })
        assertTrue(sessionTopic.any { it["type"].asText() == "SESSION_CHANGED" && it["change"].asText() == "BILLING_STARTED" })
        assertTrue(stomp.on("/topic/staff/orders").any { it["type"].asText() == "BILL_CHANGED" })
        assertTrue(stomp.on("/topic/tables").any { it["status"].asText() == "PAYMENT_PENDING" })

        // Phiên BILLING không nhận đợt mới; khách xem được phiếu tạm tính.
        place(guest, coffeeSmall()).isError(409, "SESSION_NOT_OPEN")
        val guestBill = data(api.get("/api/v1/guest/bill", guest.token).andExpect(status().isOk))
        assertEquals(159_000L, guestBill["totalAmount"].asLong())
        assertTrue(guestBill["createdBy"] == null, "phiếu cho khách không có thông tin nhân viên")
    }

    @Test
    fun `create bill is blocked - VAT not configured, pending or claimed batch, no billable item, second bill`() {
        val (guest, orderId, _) = tableWithStandardOrder()

        jdbc.update("UPDATE restaurant_settings SET vat_rate = NULL")
        createBill(orderId).isError(422, "VAT_NOT_CONFIGURED")
        jdbc.update("UPDATE restaurant_settings SET vat_rate = 0.0800")

        setMode("ALL")
        val (pendingBatch, _) = placePho(guest)
        createBill(orderId).isError(409, "BATCH_PENDING")
        api.post("/api/v1/orders/batches/$pendingBatch/claim", staff.accessToken, null).andExpect(status().isOk)
        createBill(orderId).isError(409, "BATCH_PENDING")
        assertEquals("OPEN", orderStatusOf(guest.sessionId), "bị chặn thì không đổi gì")
        assertEquals("OPEN", sessionStatus(guest.sessionId))

        val empty = guestAt("T02")
        val (_, onlyItem) = placePho(empty)
        // Khách tự hủy món PENDING duy nhất: Order không còn món tính tiền.
        api.post("/api/v1/guest/orders/items/$onlyItem/cancel", empty.token, null).andExpect(status().isOk)
        createBill(orderIdOf(empty.sessionId)).isError(409, "NO_BILLABLE_ITEMS")

        api.post("/api/v1/orders/batches/$pendingBatch/confirm", staff.accessToken, null).andExpect(status().isOk)
        createBillOk(orderId)
        createBill(orderId).isError(409, "BILL_ALREADY_ACTIVE")
        assertEquals(1L, count("SELECT count(*) FROM bill WHERE order_id = ?", orderId))
    }

    @Test
    fun `only PAYMENT_PROCESS may create a bill - plain STAFF and ADMIN get 403, MANAGER may`() {
        val (_, orderId, _) = tableWithStandardOrder()
        createBill(orderId, staff.accessToken).isError(403, "PERMISSION_DENIED")
        createBill(orderId, admin.accessToken).isError(403, "PERMISSION_DENIED")
        createBill(orderId, kitchen.accessToken).isError(403, "PERMISSION_DENIED")
        createBillOk(orderId, manager.accessToken)
    }

    @Test
    fun `REQUEST_PAYMENT of the guest is auto RESOLVED when staff creates the bill - DEC-08 B`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        api.post("/api/v1/guest/service-calls", guest.token, mapOf("type" to "REQUEST_PAYMENT")).andExpect(status().isCreated)
        api.post("/api/v1/guest/service-calls", guest.token, mapOf("type" to "CALL_STAFF")).andExpect(status().isCreated)
        assertEquals("OPEN", orderStatusOf(guest.sessionId), "khách yêu cầu thanh toán không khóa Order")

        createBillOk(orderId)

        val calls = jdbc.queryForList("SELECT type, status, resolved_by, resolution_note FROM service_call WHERE session_id = ? ORDER BY id", guest.sessionId)
        assertEquals("RESOLVED", calls[0]["status"])
        assertNull(calls[0]["resolved_by"], "hệ thống tự RESOLVED")
        assertTrue((calls[0]["resolution_note"] as String).contains("BILL-"))
        assertEquals("OPEN", calls[1]["status"], "CALL_STAFF không bị đóng khi tính tiền")
        api.post("/api/v1/guest/service-calls", guest.token, mapOf("type" to "REQUEST_PAYMENT")).isError(409, "SERVICE_CALL_STATE_INVALID")
    }

    @Test
    fun `discount at bill creation needs BILL_DISCOUNT and a reason, works on subtotal before service fee`() {
        jdbc.update("UPDATE restaurant_settings SET service_fee_rate = 0.1, service_fee_taxable = false")
        val (_, orderId, _) = tableWithStandardOrder()
        val tenPercent = mapOf("type" to "PERCENT", "value" to 10, "reason" to "Khách quen")

        createBill(orderId, cashier.accessToken, tenPercent).isError(403, "PERMISSION_DENIED")
        createBill(orderId, manager.accessToken, mapOf("type" to "PERCENT", "value" to 10, "reason" to " ")).isError(400, "VALIDATION_FAILED")
        createBill(orderId, manager.accessToken, mapOf("type" to "FIXED_AMOUNT", "value" to 159_001, "reason" to "x")).isError(422, "INVALID_DISCOUNT")
        assertEquals(0L, count("SELECT count(*) FROM bill WHERE order_id = ?", orderId))

        val bill = createBillOk(orderId, manager.accessToken, tenPercent)
        assertEquals(15_900L, bill["discountAmount"].asLong())
        assertEquals(143_100L, bill["netAmount"].asLong())
        assertEquals(14_310L, bill["serviceFeeAmount"].asLong(), "phí phục vụ 10% tính trên Net")
        assertEquals(157_410L, bill["totalAmount"].asLong())
        assertEquals(10_600L, bill["vatAmount"].asLong(), "service_fee_taxable = false -> TaxBase = Net")
        assertEquals("Khách quen", bill["discountReason"].asText())
        assertEquals(manager.id, bill["discountedBy"].asLong())
        val audit = api.auditRows("BILL_DISCOUNT_APPLIED").single()
        assertEquals("Khách quen", audit["reason"])
        assertEquals(15_900L, api.jsonOf(audit["new_value"])["discountAmount"].asLong())
    }

    @Test
    fun `a cashier granted BILL_DISCOUNT may discount by a fixed amount`() {
        fx.grant(cashier.id, Permission.BILL_DISCOUNT, manager.id)
        val (_, orderId, _) = tableWithStandardOrder()
        val bill = createBillOk(orderId, cashier.accessToken, mapOf("type" to "FIXED_AMOUNT", "value" to 9_000, "reason" to "Món ra chậm"))
        assertEquals(150_000L, bill["totalAmount"].asLong())
        assertEquals(vat(150_000), bill["vatAmount"].asLong())
    }

    @Test
    fun `changing the discount reissues the bill - old VOIDED as MANAGER_VOID, new bill replaces it - BE-29`() {
        val (_, orderId, _) = tableWithStandardOrder()
        val first = createBillOk(orderId)
        val firstId = first["id"].asLong()

        val body = mapOf("type" to "PERCENT", "value" to 20, "reason" to "Sinh nhật", "version" to first["version"].asLong())
        api.put("/api/v1/bills/$firstId/discount", cashier.accessToken, body).isError(403, "PERMISSION_DENIED")
        val second = data(api.put("/api/v1/bills/$firstId/discount", manager.accessToken, body).andExpect(status().isOk))

        val secondId = second["id"].asLong()
        assertTrue(secondId != firstId)
        assertTrue(second["billNumber"].asText() != first["billNumber"].asText(), "Bill mới có mã mới (BR-P12)")
        assertEquals(firstId, second["replacesBillId"].asLong())
        assertEquals(31_800L, second["discountAmount"].asLong())
        assertEquals(127_200L, second["totalAmount"].asLong())
        assertEquals("ACTIVE", second["status"].asText())
        val old = billRow(firstId)
        assertEquals("VOIDED", old["status"])
        assertEquals("MANAGER_VOID", old["void_type"])
        assertTrue((old["void_reason"] as String).contains("Sinh nhật"))
        assertEquals(159_000L, long(old, "total_amount"), "Bill cũ giữ nguyên snapshot (BR-P06)")
        assertEquals("LOCKED", jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String::class.java, orderId))
        assertEquals(1, api.auditRows("BILL_DISCOUNT_APPLIED").size)
        assertEquals(1, api.auditRows("BILL_VOIDED").size)

        // Gỡ giảm giá (NONE) cũng phát hành lại; giữ nguyên giảm giá thì từ chối.
        val third = data(api.put("/api/v1/bills/$secondId/discount", manager.accessToken, mapOf("type" to "NONE", "reason" to "Nhập nhầm")).andExpect(status().isOk))
        assertEquals(159_000L, third["totalAmount"].asLong())
        assertTrue(third["discountReason"].isNull)
        api.put("/api/v1/bills/${third["id"].asLong()}/discount", manager.accessToken, mapOf("type" to "NONE", "reason" to "x")).isError(422, "INVALID_DISCOUNT")
        api.put("/api/v1/bills/$firstId/discount", manager.accessToken, body).isError(409, "BILL_NOT_ACTIVE")
        assertEquals(3, data(api.get("/api/v1/orders/$orderId/bills", staff.accessToken)).size())
    }

    @Test
    fun `discount cannot be changed once a payment is PENDING or SUCCESS`() {
        val (_, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        val pending = payOk(billId, 50_000, "PENDING", "CARD_POS")["payment"]["id"].asLong()
        val body = mapOf("type" to "FIXED_AMOUNT", "value" to 1_000, "reason" to "x")
        api.put("/api/v1/bills/$billId/discount", manager.accessToken, body).isError(409, "BILL_HAS_PAYMENTS")
        confirmPayment(pending, "FAILED", "Máy POS lỗi").andExpect(status().isOk)
        payOk(billId, 10_000)
        api.put("/api/v1/bills/$billId/discount", manager.accessToken, body).isError(409, "BILL_HAS_PAYMENTS")
        assertEquals(1L, count("SELECT count(*) FROM bill WHERE order_id = ?", orderId))
    }

    @Test
    fun `read APIs - bill by order, by id, guest bill 404 before billing`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        api.get("/api/v1/guest/bill", guest.token).isError(404, "RESOURCE_NOT_FOUND")
        api.get("/api/v1/orders/$orderId/bill", staff.accessToken).isError(404, "RESOURCE_NOT_FOUND")
        val billId = createBillOk(orderId)["id"].asLong()
        assertEquals(billId, data(api.get("/api/v1/orders/$orderId/bill", staff.accessToken))["id"].asLong())
        val detail = data(api.get("/api/v1/bills/$billId", manager.accessToken).andExpect(status().isOk))
        assertEquals("T01", detail["tableCode"].asText())
        api.get("/api/v1/bills/$billId", kitchen.accessToken).isError(403, "PERMISSION_DENIED")
        api.get("/api/v1/bills/$billId", guest.token).isError(403, "PERMISSION_DENIED")
        val other = api.loggedIn("staff_two", UserRole.STAFF)
        api.get("/api/v1/bills/$billId", other.accessToken).andExpect(status().isOk)
    }
}
