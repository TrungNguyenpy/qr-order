package com.qrorder.billing.service

import com.qrorder.billing.domain.PaymentStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * BR-P13, SRS §12.1 (bảng 7 dòng, công thức đã sửa): remaining_refundable = TPS - TRS - TRP; net_collected = TPS - TRS;
 * Void hoàn tất khi TRS = TPS và không còn Refund PENDING. Refund FAILED không có mặt trong TRS/TRP.
 */
class MoneySummaryTest {

    private fun m(tps: Long, trs: Long, trp: Long) = MoneySummary(tps = tps, tpp = 0, trs = trs, trp = trp)

    @Test
    fun `SRS 12-1 table - remaining refundable, net collected and void completion`() {
        data class Row(val tps: Long, val trs: Long, val trp: Long, val remaining: Long, val net: Long, val complete: Boolean)
        val rows = listOf(
            Row(500_000, 0, 0, 500_000, 500_000, false),            // #1
            Row(500_000, 200_000, 0, 300_000, 300_000, false),      // #2
            Row(500_000, 200_000, 300_000, 0, 300_000, false),      // #3 còn Refund PENDING
            Row(500_000, 200_000, 200_000, 100_000, 300_000, false), // #4
            Row(500_000, 500_000, 0, 0, 0, true),                   // #5 hoàn đủ
            Row(500_000, 200_000, 0, 300_000, 300_000, false),      // #6 FAILED 300.000 không tính
            Row(0, 0, 0, 0, 0, true),                               // #7 không cần Refund
        )
        rows.forEachIndexed { i, r ->
            val s = m(r.tps, r.trs, r.trp)
            assertEquals(r.remaining, s.remainingRefundable, "dòng #${i + 1} remaining_refundable")
            assertEquals(r.net, s.netCollected, "dòng #${i + 1} net_collected")
            assertEquals(r.complete, s.fullyRefunded, "dòng #${i + 1} Void hoàn tất")
        }
    }

    @Test
    fun `payment status after refunds follows net collected - BR-P11 and DB-04`() {
        assertEquals(PaymentStatus.PAID, m(159_000, 0, 0).paymentStatus(159_000))
        assertEquals(PaymentStatus.PARTIALLY_PAID, m(159_000, 59_000, 0).paymentStatus(159_000))
        assertEquals(PaymentStatus.UNPAID, m(159_000, 159_000, 0).paymentStatus(159_000))
        assertEquals(PaymentStatus.PAID, m(159_000, 0, 100_000).paymentStatus(159_000), "Refund PENDING chưa trừ vào thu ròng")
    }
}
