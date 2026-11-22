package com.qrorder.support

import com.fasterxml.jackson.databind.JsonNode
import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.UserRole
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime

/**
 * Nền cho integration test Giai đoạn 6 (báo cáo): dựng dữ liệu bằng API thật rồi LÙI mốc thời gian bằng SQL để test
 * ranh giới ngày/ca/tuần/tháng một cách xác định (không phụ thuộc giờ chạy test).
 *
 * Payment/Refund/Order có trigger bất biến và CHECK nên việc lùi giờ chạy trong một transaction đặt
 * `session_replication_role = replica` (cách DbCleaner đã dùng). CHỈ DÙNG TRONG TEST.
 */
abstract class ReportIntegrationTestBase : BillingIntegrationTestBase() {

    protected fun at(iso: String): OffsetDateTime = OffsetDateTime.parse(iso)

    /** Chạy các câu lệnh UPDATE lùi giờ với trigger bị vô hiệu. */
    protected fun backdate(block: () -> Unit) {
        tx.executeWithoutResult {
            jdbc.execute("SET LOCAL session_replication_role = replica")
            block()
        }
    }

    protected fun setPaymentConfirmedAt(paymentId: Long, iso: String) = backdate {
        jdbc.update("UPDATE payment_transaction SET created_at = ?, confirmed_at = ? WHERE id = ?", at(iso), at(iso), paymentId)
    }

    protected fun setRefundConfirmedAt(refundId: Long, iso: String) = backdate {
        jdbc.update("UPDATE refund_transaction SET created_at = ?, confirmed_at = ? WHERE id = ?", at(iso), at(iso), refundId)
    }

    protected fun setOrderPaidAt(orderId: Long, iso: String) = backdate {
        jdbc.update("UPDATE orders SET paid_at = ? WHERE id = ?", at(iso), orderId)
    }

    protected fun setOrderWrittenOffAt(orderId: Long, iso: String) = backdate {
        jdbc.update("UPDATE orders SET written_off_at = ? WHERE id = ?", at(iso), orderId)
    }

    protected fun paymentIds(billId: Long): List<Long> =
        jdbc.queryForList("SELECT id FROM payment_transaction WHERE bill_id = ? ORDER BY id", Long::class.java, billId)

    protected fun refundIds(billId: Long): List<Long> =
        jdbc.queryForList("SELECT id FROM refund_transaction WHERE bill_id = ? ORDER BY id", Long::class.java, billId)

    /** Bàn [tableCode] có order chuẩn (159.000) đã thu đủ, phiên CLOSED. Trả (orderId, billId, paymentId). */
    protected fun paidOrder(tableCode: String): Triple<Long, Long, Long> {
        val (_, orderId, billId) = paidAndClosed(tableCode)
        return Triple(orderId, billId, paymentIds(billId).single())
    }

    /** Một order Phở [phoQty] + Cà phê [coffeeQty] đã phục vụ hết và thu đủ. Trả (orderId, billId). */
    protected fun paidCustomOrder(tableCode: String, phoQty: Int, coffeeQty: Int): Pair<Long, Long> {
        val guest = guestAt(tableCode)
        val items = orderConfirmed(guest, phoSmall(phoQty), coffeeSmall(coffeeQty))
        items.forEach { advance(it, "SERVED") }
        val orderId = orderIdOf(guest.sessionId)
        val bill = createBillOk(orderId)
        payOk(bill["id"].asLong(), bill["totalAmount"].asLong())
        return orderId to bill["id"].asLong()
    }

    /** Ngày nghiệp vụ hôm nay (yyyy-MM-dd) theo đồng hồ DB. */
    protected fun todayVn(): String =
        jdbc.queryForObject("SELECT to_char(now() AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD')", String::class.java)!!

    /** Cấp REPORT_VIEW cho [userId] (BR-A07). */
    protected fun grantReportView(userId: Long) {
        fx.grant(userId, Permission.REPORT_VIEW, manager.id)
    }

    protected fun adminWithReportView(): LoggedInUser =
        api.loggedIn("admin_report", UserRole.ADMIN).also { grantReportView(it.id) }

    protected fun revenue(query: String, token: String? = manager.accessToken): ResultActions = api.get("/api/v1/reports/revenue$query", token)

    protected fun revenueOk(query: String, token: String? = manager.accessToken): JsonNode = data(revenue(query, token).andExpect(status().isOk))

    protected fun periodOf(report: JsonNode, label: String): JsonNode =
        report["periods"].single { it["label"].asText() == label }

    protected fun methodOf(period: JsonNode, method: String): JsonNode =
        period["byMethod"].single { it["method"].asText() == method }
}
