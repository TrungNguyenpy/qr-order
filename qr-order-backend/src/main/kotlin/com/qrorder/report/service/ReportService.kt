package com.qrorder.report.service

import com.qrorder.billing.domain.PaymentMethod
import com.qrorder.common.config.BusinessTime
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.time.DbClock
import com.qrorder.report.domain.ReportGroupBy
import com.qrorder.report.domain.ReportProperties
import com.qrorder.report.domain.ReportRange
import com.qrorder.report.domain.ReportWindow
import com.qrorder.report.domain.ReportWindows
import com.qrorder.report.domain.ShiftCatalog
import com.qrorder.report.dto.ItemSummaryResponse
import com.qrorder.report.dto.MethodAmountResponse
import com.qrorder.report.dto.ReasonCountResponse
import com.qrorder.report.dto.RevenuePeriodResponse
import com.qrorder.report.dto.RevenueReportResponse
import com.qrorder.report.dto.RevenueTotalsResponse
import com.qrorder.report.dto.ServiceSegmentResponse
import com.qrorder.report.dto.ServiceTimeResponse
import com.qrorder.report.dto.SummaryReportResponse
import com.qrorder.report.dto.TopProductResponse
import com.qrorder.report.dto.TopProductSort
import com.qrorder.report.dto.TopProductsResponse
import com.qrorder.report.repository.MethodRow
import com.qrorder.report.repository.ReportRepository
import com.qrorder.report.repository.SegmentStats
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Báo cáo doanh thu và vận hành (FR-RPT-01, FR-RPT-02). Chỉ đọc; không đổi dữ liệu nghiệp vụ.
 *
 * Quy tắc số liệu (BE-34..BE-36, đã chốt):
 * - Doanh thu ròng theo dòng tiền thực: Payment SUCCESS tính theo `confirmed_at` của Payment, Refund SUCCESS trừ theo
 *   `confirmed_at` của Refund, nên báo cáo của ngày đã qua không đổi khi có Refund ở ngày sau.
 * - Thất thu (write-off) hiển thị riêng, không cộng/trừ vào doanh thu (BR-P15).
 * - Món bán chạy: món tính tiền của Order PAID theo ngày thanh toán, doanh số theo `line_total`.
 */
@Service
@Transactional(readOnly = true)
class ReportService(
    private val repository: ReportRepository,
    private val shiftCatalog: ShiftCatalog,
    private val properties: ReportProperties,
    private val dbClock: DbClock,
) {
    fun resolveRange(from: String?, to: String?): ReportRange {
        val today = dbClock.now().atZoneSameInstant(BusinessTime.ZONE).toLocalDate()
        return ReportRange.resolve(from, to, today, properties.maxRangeDays)
    }

    fun revenue(range: ReportRange, groupBy: ReportGroupBy, shift: String?): RevenueReportResponse {
        val shiftCode = shift?.takeIf { it.isNotBlank() }
        val windows = ReportWindows.build(range, groupBy, shiftCatalog.shifts, shiftCode)
        val payments = if (windows.isEmpty()) emptyList() else repository.paymentsByMethod(windows)
        val refunds = if (windows.isEmpty()) emptyList() else repository.refundsByMethod(windows)
        val billsByWindow = if (windows.isEmpty()) emptyMap() else repository.paymentBillsByWindow(windows)
        val writeOffs = if (windows.isEmpty()) emptyMap() else repository.writeOffsByWindow(windows)
        val billsTotal = if (windows.isEmpty()) 0 else repository.paymentBillsTotal(windows)

        val periods = windows.map { w ->
            val byMethod = methodAmounts(payments.filter { it.windowIndex == w.index }, refunds.filter { it.windowIndex == w.index })
            val wo = writeOffs[w.index]
            period(w, byMethod, billsByWindow[w.index] ?: 0, wo?.count ?: 0, wo?.amount ?: 0L)
        }
        val totalMethods = PaymentMethod.entries.map { m ->
            MethodAmountResponse(
                m,
                periods.sumOf { p -> p.byMethod.first { it.method == m }.paymentCount },
                periods.sumOf { p -> p.byMethod.first { it.method == m }.paymentAmount },
                periods.sumOf { p -> p.byMethod.first { it.method == m }.refundCount },
                periods.sumOf { p -> p.byMethod.first { it.method == m }.refundAmount },
                periods.sumOf { p -> p.byMethod.first { it.method == m }.netAmount },
            )
        }
        val gross = periods.sumOf { it.grossRevenue }
        val refunded = periods.sumOf { it.refundedAmount }
        return RevenueReportResponse(
            from = range.from, to = range.to, fromDate = range.fromDate, toDate = range.toDate, groupBy = groupBy, shift = shiftCode,
            totals = RevenueTotalsResponse(
                grossRevenue = gross, refundedAmount = refunded, netRevenue = gross - refunded,
                paymentCount = periods.sumOf { it.paymentCount }, billCount = billsTotal, refundCount = periods.sumOf { it.refundCount },
                writtenOffCount = periods.sumOf { it.writtenOffCount }, writtenOffShortfall = periods.sumOf { it.writtenOffShortfall },
                byMethod = totalMethods,
            ),
            periods = periods,
        )
    }

    fun summary(range: ReportRange): SummaryReportResponse {
        // Một kỳ duy nhất phủ toàn bộ khoảng, dùng lại truy vấn Refund/Write-off theo kỳ.
        val window = listOf(ReportWindow(0, "range", range.fromDate, range.from, range.to))
        val refunds = repository.refundsByMethod(window)
        val writeOff = repository.writeOffsByWindow(window)[0]
        val cohort = repository.itemCohort(range.from, range.to)
        val batches = repository.batches(range.from, range.to)
        return SummaryReportResponse(
            from = range.from, to = range.to, fromDate = range.fromDate, toDate = range.toDate,
            writtenOffCount = writeOff?.count ?: 0, writtenOffShortfall = writeOff?.amount ?: 0L,
            refundCount = refunds.sumOf { it.count }, refundedAmount = refunds.sumOf { it.amount },
            sessionsOpened = repository.sessionsOpened(range.from, range.to),
            batchesSubmitted = batches.total, batchesByStaff = batches.byStaff,
            items = ItemSummaryResponse(
                totalItems = cohort.total, cancelledItems = cohort.cancelled, rejectedItems = cohort.rejected,
                cancelRate = rate(cohort.cancelled, cohort.total), rejectRate = rate(cohort.rejected, cohort.total),
                wastedItems = cohort.wasted, wastedValue = cohort.wastedValue,
                byReason = repository.itemReasons(range.from, range.to).map { ReasonCountResponse(it.reasonCode, it.count) },
            ),
            ordersNeedingAttention = repository.ordersNeedingAttention(),
            billsVoidPending = repository.billsVoidPending(),
        )
    }

    fun topProducts(range: ReportRange, limit: Int?, categoryId: Long?, sortBy: TopProductSort?): TopProductsResponse {
        val effectiveLimit = limit ?: DEFAULT_TOP_LIMIT
        if (effectiveLimit !in 1..MAX_TOP_LIMIT) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "limit phải từ 1 đến $MAX_TOP_LIMIT")
        }
        val sort = sortBy ?: TopProductSort.QUANTITY
        val rows = repository.topProducts(range.from, range.to, categoryId, sort, effectiveLimit)
        return TopProductsResponse(
            from = range.from, to = range.to, fromDate = range.fromDate, toDate = range.toDate,
            sortBy = sort, categoryId = categoryId, limit = effectiveLimit,
            products = rows.mapIndexed { i, r ->
                TopProductResponse(i + 1, r.productId, r.productName, r.categoryId, r.categoryName, r.quantity, r.sales, r.orderCount)
            },
        )
    }

    fun serviceTime(range: ReportRange): ServiceTimeResponse {
        val row = repository.serviceTime(range.from, range.to)
        return ServiceTimeResponse(
            from = range.from, to = range.to, fromDate = range.fromDate, toDate = range.toDate, servedItems = row.servedItems,
            segments = listOf(
                segment(SEGMENT_CONFIRMATION, row.confirmation),
                segment(SEGMENT_KITCHEN_QUEUE, row.kitchenQueue),
                segment(SEGMENT_COOKING, row.cooking),
                segment(SEGMENT_DELIVERY, row.delivery),
                segment(SEGMENT_TOTAL, row.total),
            ),
        )
    }

    // ------------------------------------------------------------------------------------------ Nội bộ

    private fun period(w: ReportWindow, byMethod: List<MethodAmountResponse>, bills: Int, writtenOffCount: Int, shortfall: Long): RevenuePeriodResponse {
        val gross = byMethod.sumOf { it.paymentAmount }
        val refunded = byMethod.sumOf { it.refundAmount }
        return RevenuePeriodResponse(
            label = w.label, date = w.date, from = w.start, to = w.end, shiftCode = w.shiftCode, shiftName = w.shiftName,
            grossRevenue = gross, refundedAmount = refunded, netRevenue = gross - refunded,
            paymentCount = byMethod.sumOf { it.paymentCount }, billCount = bills, refundCount = byMethod.sumOf { it.refundCount },
            writtenOffCount = writtenOffCount, writtenOffShortfall = shortfall, byMethod = byMethod,
        )
    }

    /** Đủ cả 4 phương thức (kể cả 0) để client không phải tự bù. */
    private fun methodAmounts(payments: List<MethodRow>, refunds: List<MethodRow>): List<MethodAmountResponse> =
        PaymentMethod.entries.map { m ->
            val p = payments.filter { it.method == m }
            val r = refunds.filter { it.method == m }
            val paid = p.sumOf { it.amount }
            val refunded = r.sumOf { it.amount }
            MethodAmountResponse(m, p.sumOf { it.count }, paid, r.sumOf { it.count }, refunded, paid - refunded)
        }

    private fun rate(part: Int, total: Int): BigDecimal =
        if (total == 0) BigDecimal.ZERO.setScale(4) else BigDecimal(part).divide(BigDecimal(total), 4, RoundingMode.HALF_UP)

    private fun segment(name: String, stats: SegmentStats) = ServiceSegmentResponse(name, stats.sampleCount, stats.average, stats.median)

    companion object {
        const val DEFAULT_TOP_LIMIT = 10
        const val MAX_TOP_LIMIT = 100
        const val SEGMENT_CONFIRMATION = "CONFIRMATION"
        const val SEGMENT_KITCHEN_QUEUE = "KITCHEN_QUEUE"
        const val SEGMENT_COOKING = "COOKING"
        const val SEGMENT_DELIVERY = "DELIVERY"
        const val SEGMENT_TOTAL = "TOTAL"
    }
}
