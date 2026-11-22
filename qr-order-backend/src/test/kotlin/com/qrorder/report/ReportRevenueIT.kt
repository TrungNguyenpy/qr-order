package com.qrorder.report

import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.ReportIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

/**
 * GĐ6 - báo cáo doanh thu (FR-RPT-01, BE-34, BE-35): ranh giới ngày theo Asia/Ho_Chi_Minh, doanh thu ròng theo dòng tiền
 * thực (Payment và Refund theo ngày xác nhận SUCCESS), thất thu hiển thị riêng, nhóm tuần/tháng/ca, khoảng from/to.
 * Mốc thời gian được lùi bằng SQL ([ReportIntegrationTestBase.backdate]) nên kết quả không phụ thuộc giờ chạy test.
 */
class ReportRevenueIT : ReportIntegrationTestBase() {

    @Test
    fun `without from and to the report covers today and counts a fresh payment`() {
        paidOrder("T01")

        val report = revenueOk("")

        assertEquals(todayVn(), report["fromDate"].asText())
        assertEquals(todayVn(), report["toDate"].asText())
        assertEquals("DAY", report["groupBy"].asText())
        val today = report["periods"].single()
        assertEquals(todayVn(), today["label"].asText())
        assertEquals(159_000L, today["grossRevenue"].asLong())
        assertEquals(0L, today["refundedAmount"].asLong())
        assertEquals(159_000L, today["netRevenue"].asLong())
        assertEquals(1, today["paymentCount"].asInt())
        assertEquals(1, today["billCount"].asInt())
        assertEquals(listOf("CASH", "BANK_TRANSFER", "CARD_POS", "OTHER"), today["byMethod"].map { it["method"].asText() })
        assertEquals(159_000L, methodOf(today, "CASH")["paymentAmount"].asLong())
        assertEquals(0L, methodOf(today, "CARD_POS")["paymentAmount"].asLong())
        assertEquals(159_000L, report["totals"]["netRevenue"].asLong())
    }

    @Test
    fun `an empty day is zero filled with all payment methods`() {
        val report = revenueOk("?from=2026-01-05&to=2026-01-06")

        assertEquals(listOf("2026-01-05", "2026-01-06"), report["periods"].map { it["label"].asText() })
        report["periods"].forEach {
            assertEquals(0L, it["grossRevenue"].asLong())
            assertEquals(4, it["byMethod"].size())
        }
        assertEquals(0L, report["totals"]["netRevenue"].asLong())
        assertEquals(0, report["totals"]["billCount"].asInt())
    }

    @Test
    fun `day boundaries follow Vietnam time - 23h59 and 00h00 fall on different days and outside payments are excluded`() {
        val times = listOf(
            "2026-11-19T23:59:59+07:00" to "before the range",
            "2026-11-20T23:59:00+07:00" to "day 1 last minute",
            "2026-11-21T00:00:00+07:00" to "day 2 first instant",
            "2026-11-21T23:59:59+07:00" to "day 2 last second",
            "2026-11-22T00:00:00+07:00" to "after the range",
        )
        times.forEachIndexed { i, (iso, _) -> setPaymentConfirmedAt(paidOrder("T0${i + 1}").third, iso) }

        val report = revenueOk("?from=2026-11-20&to=2026-11-21")

        assertEquals(listOf("2026-11-20", "2026-11-21"), report["periods"].map { it["label"].asText() })
        val day1 = periodOf(report, "2026-11-20")
        val day2 = periodOf(report, "2026-11-21")
        assertEquals(159_000L, day1["grossRevenue"].asLong())
        assertEquals(1, day1["paymentCount"].asInt())
        assertEquals(318_000L, day2["grossRevenue"].asLong())
        assertEquals(2, day2["paymentCount"].asInt())
        assertEquals(477_000L, report["totals"]["grossRevenue"].asLong())
        assertEquals(3, report["totals"]["billCount"].asInt())
        assertEquals(3, report["totals"]["paymentCount"].asInt())
    }

    @Test
    fun `a refund is subtracted on its own confirmation day and earlier days stay unchanged`() {
        val (_, billId, paymentId) = paidOrder("T01")
        requestVoidOk(billId)
        refundOk(billId, 159_000)
        setPaymentConfirmedAt(paymentId, "2026-11-20T10:00:00+07:00")
        setRefundConfirmedAt(refundIds(billId).single(), "2026-11-21T10:00:00+07:00")

        val both = revenueOk("?from=2026-11-20&to=2026-11-21")
        val day1 = periodOf(both, "2026-11-20")
        val day2 = periodOf(both, "2026-11-21")
        assertEquals(159_000L, day1["netRevenue"].asLong())
        assertEquals(0L, day1["refundedAmount"].asLong())
        assertEquals(0L, day2["grossRevenue"].asLong())
        assertEquals(159_000L, day2["refundedAmount"].asLong())
        assertEquals(-159_000L, day2["netRevenue"].asLong())
        assertEquals(1, day2["refundCount"].asInt())
        assertEquals(-159_000L, methodOf(day2, "CASH")["netAmount"].asLong())
        assertEquals(0L, both["totals"]["netRevenue"].asLong())
        assertEquals(159_000L, both["totals"]["refundedAmount"].asLong())
        assertEquals(1, both["totals"]["refundCount"].asInt())

        val closedDay = revenueOk("?from=2026-11-20&to=2026-11-20")
        assertEquals(159_000L, closedDay["totals"]["netRevenue"].asLong(), "BE-35 - ngày đã chốt không đổi khi Refund xảy ra ngày sau")
    }

    @Test
    fun `a write-off is shown separately and never added to or subtracted from revenue`() {
        val (guest, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        payOk(billId, 59_000)
        api.post("/api/v1/sessions/${guest.sessionId}/force-close", manager.accessToken, mapOf("reason" to "Khách bỏ về"))
            .andExpect(status().isOk)
        setPaymentConfirmedAt(paymentIds(billId).single(), "2026-11-20T10:00:00+07:00")
        setOrderWrittenOffAt(orderId, "2026-11-21T22:00:00+07:00")

        val report = revenueOk("?from=2026-11-20&to=2026-11-21")

        val day1 = periodOf(report, "2026-11-20")
        val day2 = periodOf(report, "2026-11-21")
        assertEquals(59_000L, day1["netRevenue"].asLong())
        assertEquals(0, day1["writtenOffCount"].asInt())
        assertEquals(0L, day2["netRevenue"].asLong())
        assertEquals(1, day2["writtenOffCount"].asInt())
        assertEquals(100_000L, day2["writtenOffShortfall"].asLong())
        assertEquals(59_000L, report["totals"]["grossRevenue"].asLong())
        assertEquals(59_000L, report["totals"]["netRevenue"].asLong())
        assertEquals(100_000L, report["totals"]["writtenOffShortfall"].asLong())
    }

    @Test
    fun `a bill paid with two methods counts one bill and splits the amount by method`() {
        val (_, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        payOk(billId, 100_000, "SUCCESS", "CASH")
        payOk(billId, 59_000, "SUCCESS", "BANK_TRANSFER")
        paymentIds(billId).forEach { setPaymentConfirmedAt(it, "2026-11-21T12:00:00+07:00") }

        val report = revenueOk("?from=2026-11-21&to=2026-11-21")

        val day = report["periods"].single()
        assertEquals(159_000L, day["grossRevenue"].asLong())
        assertEquals(2, day["paymentCount"].asInt())
        assertEquals(1, day["billCount"].asInt())
        assertEquals(100_000L, methodOf(day, "CASH")["paymentAmount"].asLong())
        assertEquals(59_000L, methodOf(day, "BANK_TRANSFER")["paymentAmount"].asLong())
        assertEquals(1, report["totals"]["billCount"].asInt())
        assertEquals(59_000L, methodOf(report["totals"], "BANK_TRANSFER")["paymentAmount"].asLong())
    }

    @Test
    fun `failed and pending payments are not revenue`() {
        val (_, orderId, _) = tableWithStandardOrder()
        val billId = createBillOk(orderId)["id"].asLong()
        val failedId = payOk(billId, 59_000, "PENDING", "CARD_POS")["payment"]["id"].asLong()
        confirmPayment(failedId, "FAILED", "Thẻ bị từ chối").andExpect(status().isOk)
        payOk(billId, 50_000, "PENDING", "BANK_TRANSFER")
        backdate {
            jdbc.update("UPDATE payment_transaction SET created_at = ?, confirmed_at = ? WHERE status = 'FAILED'", at("2026-11-21T12:00:00+07:00"), at("2026-11-21T12:00:00+07:00"))
        }

        val report = revenueOk("?from=2026-11-21&to=2026-11-21")

        assertEquals(0L, report["totals"]["grossRevenue"].asLong())
        assertEquals(0, report["totals"]["paymentCount"].asInt())
        assertEquals(0, report["totals"]["billCount"].asInt())
    }

    @Test
    fun `week grouping uses ISO weeks from Monday and clips the first week`() {
        setPaymentConfirmedAt(paidOrder("T01").third, "2026-11-15T12:00:00+07:00")
        setPaymentConfirmedAt(paidOrder("T02").third, "2026-11-16T12:00:00+07:00")
        setPaymentConfirmedAt(paidOrder("T03").third, "2026-12-01T12:00:00+07:00")

        val report = revenueOk("?from=2026-11-01&to=2026-12-31&groupBy=WEEK")

        assertEquals("2026-W44", report["periods"][0]["label"].asText())
        assertEquals("2026-10-26", report["periods"][0]["date"].asText())
        assertEquals(159_000L, periodOf(report, "2026-W46")["grossRevenue"].asLong(), "chủ nhật 15/11 thuộc tuần 46")
        assertEquals(159_000L, periodOf(report, "2026-W47")["grossRevenue"].asLong(), "thứ hai 16/11 mở tuần 47")
        assertEquals(159_000L, periodOf(report, "2026-W49")["grossRevenue"].asLong())
        assertEquals(477_000L, report["totals"]["grossRevenue"].asLong())
        assertEquals(0L, periodOf(report, "2026-W45")["grossRevenue"].asLong())
    }

    @Test
    fun `month grouping sums every payment of the calendar month in Vietnam time`() {
        setPaymentConfirmedAt(paidOrder("T01").third, "2026-11-30T23:30:00+07:00")
        setPaymentConfirmedAt(paidOrder("T02").third, "2026-12-01T00:30:00+07:00")
        setPaymentConfirmedAt(paidOrder("T03").third, "2026-11-02T08:00:00+07:00")

        val report = revenueOk("?from=2026-11-01&to=2026-12-31&groupBy=MONTH")

        assertEquals(listOf("2026-11", "2026-12"), report["periods"].map { it["label"].asText() })
        assertEquals(318_000L, periodOf(report, "2026-11")["grossRevenue"].asLong())
        assertEquals(159_000L, periodOf(report, "2026-12")["grossRevenue"].asLong())
        assertEquals("2026-12-01", periodOf(report, "2026-12")["date"].asText())
    }

    @Test
    fun `shift grouping attributes the night shift to the day it started`() {
        setPaymentConfirmedAt(paidOrder("T01").third, "2026-11-21T07:00:00+07:00")
        setPaymentConfirmedAt(paidOrder("T02").third, "2026-11-21T15:00:00+07:00")
        setPaymentConfirmedAt(paidOrder("T03").third, "2026-11-21T23:30:00+07:00")
        setPaymentConfirmedAt(paidOrder("T04").third, "2026-11-22T02:00:00+07:00")

        val report = revenueOk("?from=2026-11-21&to=2026-11-22&groupBy=SHIFT")

        assertEquals(
            listOf("2026-11-21 MORNING", "2026-11-21 EVENING", "2026-11-21 NIGHT", "2026-11-22 MORNING", "2026-11-22 EVENING", "2026-11-22 NIGHT"),
            report["periods"].map { it["label"].asText() },
        )
        assertEquals(159_000L, periodOf(report, "2026-11-21 MORNING")["grossRevenue"].asLong())
        assertEquals(159_000L, periodOf(report, "2026-11-21 EVENING")["grossRevenue"].asLong())
        val night = periodOf(report, "2026-11-21 NIGHT")
        assertEquals(318_000L, night["grossRevenue"].asLong(), "23h30 và 02h00 sáng hôm sau cùng thuộc ca đêm 21/11")
        assertEquals("NIGHT", night["shiftCode"].asText())
        assertEquals("Ca đêm", night["shiftName"].asText())
        assertEquals(0L, periodOf(report, "2026-11-22 NIGHT")["grossRevenue"].asLong())
        assertEquals(636_000L, report["totals"]["grossRevenue"].asLong())
        assertEquals(4, report["totals"]["billCount"].asInt())

        val onlyNight = revenueOk("?from=2026-11-21&to=2026-11-22&groupBy=SHIFT&shift=NIGHT")
        assertEquals(listOf("2026-11-21 NIGHT", "2026-11-22 NIGHT"), onlyNight["periods"].map { it["label"].asText() })
        assertEquals("NIGHT", onlyNight["shift"].asText())
        assertEquals(318_000L, onlyNight["totals"]["grossRevenue"].asLong())

        val nextDayOnly = revenueOk("?from=2026-11-22&to=2026-11-22&groupBy=SHIFT")
        assertEquals(0L, nextDayOnly["totals"]["grossRevenue"].asLong(), "ca đêm bắt đầu 21/11 không thuộc khoảng chỉ có ngày 22/11")
    }

    @Test
    fun `a date time range with an offset is clipped exactly`() {
        setPaymentConfirmedAt(paidOrder("T01").third, "2026-11-21T09:59:59+07:00")
        setPaymentConfirmedAt(paidOrder("T02").third, "2026-11-21T10:00:00+07:00")
        setPaymentConfirmedAt(paidOrder("T03").third, "2026-11-21T11:59:59+07:00")
        setPaymentConfirmedAt(paidOrder("T04").third, "2026-11-21T12:00:00+07:00")

        val report = revenueOk("?from=2026-11-21T10:00:00+07:00&to=2026-11-21T12:00:00+07:00")

        assertEquals(318_000L, report["totals"]["grossRevenue"].asLong(), "cận dưới bao gồm, cận trên loại trừ")
        assertEquals("2026-11-21", report["periods"].single()["label"].asText())
    }

    @Test
    fun `shift errors - unknown code and shift without SHIFT grouping`() {
        revenue("?from=2026-11-21&to=2026-11-21&groupBy=SHIFT&shift=AFTERNOON").isError(422, "REPORT_SHIFT_NOT_CONFIGURED")
        revenue("?from=2026-11-21&to=2026-11-21&groupBy=DAY&shift=NIGHT").isError(400, "VALIDATION_FAILED")
        revenue("?groupBy=YEAR").isError(400, "MALFORMED_REQUEST")
    }

    @Test
    fun `invalid ranges are rejected with INVALID_REPORT_RANGE`() {
        revenue("?from=2026-11-20").isError(400, "INVALID_REPORT_RANGE")
        revenue("?to=2026-11-20").isError(400, "INVALID_REPORT_RANGE")
        revenue("?from=2026-11-22&to=2026-11-21").isError(400, "INVALID_REPORT_RANGE")
        revenue("?from=hom-qua&to=2026-11-21").isError(400, "INVALID_REPORT_RANGE")
        revenue("?from=2025-01-01&to=2026-11-21").isError(400, "INVALID_REPORT_RANGE")
        revenue("?from=2025-11-21&to=2026-11-20").andExpect(status().isOk)
    }

    @Test
    fun `only users with REPORT_VIEW can read reports`() {
        val adminWith = adminWithReportView()
        val staffWith = api.loggedIn("staff_report", com.qrorder.auth.domain.UserRole.STAFF).also { grantReportView(it.id) }
        val query = "?from=2026-11-21&to=2026-11-21"

        revenue(query, manager.accessToken).andExpect(status().isOk)
        revenue(query, adminWith.accessToken).andExpect(status().isOk)
        revenue(query, staffWith.accessToken).andExpect(status().isOk)
        revenue(query, admin.accessToken).isError(403, "PERMISSION_DENIED")
        revenue(query, staff.accessToken).isError(403, "PERMISSION_DENIED")
        revenue(query, kitchen.accessToken).isError(403, "PERMISSION_DENIED")
        revenue(query, guestAt("T01").token).isError(403, "PERMISSION_DENIED")
        revenue(query, null).isError(401, "UNAUTHENTICATED")
    }

    @Test
    fun `revoking REPORT_VIEW removes access at once`() {
        val adminWith = api.loggedIn("admin_revoked", com.qrorder.auth.domain.UserRole.ADMIN)
        val grantId = fx.grant(adminWith.id, com.qrorder.auth.domain.Permission.REPORT_VIEW, manager.id)
        revenue("", adminWith.accessToken).andExpect(status().isOk)

        fx.revoke(grantId, manager.id)

        revenue("", adminWith.accessToken).isError(403, "PERMISSION_DENIED")
    }
}
