package com.qrorder.billing

import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.BillingIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GĐ5b - Bill thay thế (BR-P16, O4, P-3, SRS §6.14), Order chờ Bill thay thế (BR-P17), Manager mở lại phiên sau Void
 * (BR-P08 b) và force-close khi Order chờ Bill thay thế (BR-P15) - SRS §12.3 #6 - #9.
 */
class ReplacementBillIT : BillingIntegrationTestBase() {

    @Test
    fun `full refund then replacement bill - attention cleared, no money carried over, paying it closes the BILLING session`() {
        val (guest, orderId, items) = tableWithStandardOrder()
        advance(items[0], "PREPARING") // món còn nấu: phiên giữ BILLING sau khi thu đủ
        val oldBillId = createBillOk(orderId)["id"].asLong()
        payOk(oldBillId, 159_000)
        assertEquals("BILLING", sessionStatus(guest.sessionId))
        assertEquals("PAID", orderStatusOf(guest.sessionId))

        requestVoidOk(oldBillId)
        assertEquals("LOCKED", orderStatusOf(guest.sessionId))
        assertEquals(true, refundOk(oldBillId, 159_000)["voidCompleted"].asBoolean())

        // SRS 12.3 #8: Manager tạo Bill thay thế (kèm giảm giá) - needs_attention được xóa, Bill mới UNPAID.
        val replacement = data(
            createReplacement(orderId, discount = mapOf("type" to "PERCENT", "value" to 10, "reason" to "Xin lỗi vì sai món"))
                .andExpect(status().isCreated),
        )
        val newBillId = replacement["id"].asLong()
        assertEquals(oldBillId, replacement["replacesBillId"].asLong())
        assertEquals("ACTIVE", replacement["status"].asText())
        assertEquals("UNPAID", replacement["paymentStatus"].asText())
        assertEquals(143_100L, replacement["totalAmount"].asLong())
        assertEquals(0L, replacement["paidAmount"].asLong(), "không chuyển tiền từ Bill cũ (BR-P13 (7))")
        assertEquals(false, replacement["orderNeedsAttention"].asBoolean())
        assertEquals(false, orderRow(orderId)["needs_attention"])
        assertEquals("LOCKED", orderStatusOf(guest.sessionId))
        assertEquals(1, api.auditRows("BILL_REPLACEMENT_CREATED").size)
        assertEquals(1, api.auditRows("BILL_DISCOUNT_APPLIED").size)
        assertEquals("VOIDED", billRow(oldBillId)["status"])
        assertEquals(0, data(api.get("/api/v1/billing/attention", manager.accessToken)).size())

        // Thu lại trên Bill mới: Order PAID; phiên BILLING đóng khi món cuối được phục vụ (SRS 6.14).
        payOk(newBillId, 143_100)
        assertEquals("PAID", orderStatusOf(guest.sessionId))
        assertEquals("BILLING", sessionStatus(guest.sessionId))
        advance(items[0], "SERVED")
        advance(items[1], "SERVED")
        assertEquals("CLOSED", sessionStatus(guest.sessionId))
        assertEquals(2, data(api.get("/api/v1/orders/$orderId/bills", staff.accessToken)).size())
    }

    @Test
    fun `an order awaiting a replacement stays LOCKED - staff and guests cannot change it - SRS 12-3 7 and 9`() {
        val (guest, orderId, items) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        requestVoidOk(billId)

        createReplacement(orderId, token = cashier.accessToken).isError(403, "PERMISSION_DENIED")
        createReplacement(orderId, token = admin.accessToken).isError(403, "PERMISSION_DENIED")
        createBill(orderId).isError(409, "ORDER_NOT_OPEN")
        place(guest, phoSmall()).isError(409, "SESSION_NOT_OPEN")
        api.post("/api/v1/guest/orders/items/${items[1]}/cancel", guest.token, null).isError(409, "ORDER_NOT_OPEN")
        // Staff chỉ có PAYMENT_PROCESS không mở lại được phiên sau Void (cần SESSION_REOPEN - BR-P08 b).
        api.post("/api/v1/sessions/${guest.sessionId}/reopen", cashier.accessToken, mapOf("reason" to "x")).isError(403, "PERMISSION_DENIED")
        // Staff gửi Manager hỗ trợ.
        api.post("/api/v1/service-calls", cashier.accessToken, mapOf("sessionId" to guest.sessionId, "note" to "Cần Bill thay thế")).andExpect(status().isCreated)

        val order = orderRow(orderId)
        assertEquals("LOCKED", order["status"])
        assertEquals(true, order["needs_attention"])
        assertEquals("BILLING", sessionStatus(guest.sessionId), "phiên không tự đóng")
        assertEquals(1L, count("SELECT count(*) FROM bill WHERE order_id = ?", orderId))
    }

    @Test
    fun `replacement bill is refused unless the order awaits one`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        createReplacement(orderId).isError(409, "REPLACEMENT_BILL_NOT_ALLOWED")
        val billId = createBillOk(orderId)["id"].asLong()
        createReplacement(orderId).isError(409, "BILL_ALREADY_ACTIVE")

        // Staff mở lại phiên (BR-P08 a): Bill VOIDED (SESSION_REOPEN) nhưng Order OPEN - không phải luồng Bill thay thế.
        api.post("/api/v1/sessions/${guest.sessionId}/reopen", cashier.accessToken, mapOf("reason" to "Khách gọi thêm")).andExpect(status().isOk)
        assertEquals("VOIDED", billRow(billId)["status"])
        createReplacement(orderId).isError(409, "REPLACEMENT_BILL_NOT_ALLOWED")

        // Order chờ Bill thay thế nhưng VAT chưa cấu hình -> 422, không đổi gì (BR-P16, BR-P03).
        val other = tableWithStandardOrder("T02")
        val otherBill = createBillOk(other.second)["id"].asLong()
        requestVoidOk(otherBill)
        jdbc.update("UPDATE restaurant_settings SET vat_rate = NULL")
        createReplacement(other.second).isError(422, "VAT_NOT_CONFIGURED")
        assertEquals(true, orderRow(other.second)["needs_attention"])
        jdbc.update("UPDATE restaurant_settings SET vat_rate = 0.0800")
        createReplacement(other.second).andExpect(status().isCreated)
    }

    @Test
    fun `manager reopens the session after a void - order OPEN, attention cleared, staff bills again - BR-P08 b`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        payOk(billId, 59_000)
        requestVoidOk(billId)
        // Bill VOID_PENDING: chưa mở lại được.
        api.post("/api/v1/sessions/${guest.sessionId}/reopen", manager.accessToken, mapOf("reason" to "x")).isError(409, "BILL_VOID_PENDING")
        refundOk(billId, 59_000)

        val reopened = data(api.post("/api/v1/sessions/${guest.sessionId}/reopen", manager.accessToken, mapOf("reason" to "Khách đổi món")).andExpect(status().isOk))
        assertEquals("OPEN", reopened["sessionStatus"].asText())
        assertEquals("OPEN", reopened["orderStatus"].asText())
        assertEquals("OCCUPIED", reopened["tableStatus"].asText())
        assertEquals(billId, reopened["voidedBillId"].asLong())
        val order = orderRow(orderId)
        assertEquals(false, order["needs_attention"])
        assertEquals("Khách đổi món", api.auditRows("SESSION_REOPENED").single()["reason"])

        // Khách gọi thêm, Staff tính tiền lại bình thường (không phải Bill thay thế).
        orderConfirmed(guest, coffeeSmall())
        val next = createBillOk(orderId)
        assertEquals(188_000L, next["totalAmount"].asLong())
        assertTrue(next["replacesBillId"].isNull)
    }

    @Test
    fun `force-close an order awaiting replacement writes it off from the billable items and clears the attention flag`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        payOk(billId, 59_000)
        requestVoidOk(billId)
        refundOk(billId, 59_000)

        api.post("/api/v1/sessions/${guest.sessionId}/force-close", manager.accessToken, mapOf("reason" to "Khách bỏ về")).andExpect(status().isOk)
        val order = orderRow(orderId)
        assertEquals("WRITTEN_OFF", order["status"])
        assertEquals(159_000L, long(order, "shortfall_amount"), "BR-P15 - không còn Bill hiện hành: tính từ món, giảm giá 0; thu ròng 0")
        assertEquals(false, order["needs_attention"])
        assertEquals("VOIDED", billRow(billId)["status"])
        assertEquals("FORCE_CLOSED", sessionStatus(guest.sessionId))
    }
}
