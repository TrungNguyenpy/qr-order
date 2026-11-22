package com.qrorder.report.service

import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.report.csv.CsvWriter
import com.qrorder.report.domain.ReportGroupBy
import com.qrorder.report.domain.ReportRange
import com.qrorder.report.dto.RevenuePeriodResponse
import com.qrorder.report.dto.TopProductSort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

object ReportAuditActions {
    /** Xuất báo cáo CSV (dữ liệu tài chính nên ghi audit: ai, báo cáo nào, khoảng nào). */
    const val REPORT_EXPORTED = "REPORT_EXPORTED"
}

object ReportAuditEntityTypes {
    const val REPORT = "report"
}

/** Một tệp CSV đã dựng: tên tệp gợi ý và nội dung (UTF-8 có BOM). */
class CsvExport(val fileName: String, val content: ByteArray)

/**
 * Xuất báo cáo CSV (BE-38, FR-RPT-03). Chạy trong một transaction ghi (không readOnly) vì phải ghi audit `REPORT_EXPORTED`;
 * số liệu lấy từ [ReportService] nên giống hệt API JSON.
 */
@Service
class ReportExportService(
    private val reports: ReportService,
    private val auditService: AuditService,
) {
    @Transactional
    fun revenueCsv(userId: Long, range: ReportRange, groupBy: ReportGroupBy, shift: String?): CsvExport {
        val report = reports.revenue(range, groupBy, shift)
        val header = listOf(
            "Kỳ", "Ngày", "Ca", "Từ", "Đến", "Doanh thu gộp", "Hoàn tiền", "Doanh thu ròng", "Số giao dịch thu", "Số Bill",
            "Số giao dịch hoàn", "Số đơn thất thu", "Thất thu",
        ) + com.qrorder.billing.domain.PaymentMethod.entries.map { "Thu $it" }
        val rows = report.periods.map { p -> periodRow(p) } + listOf(totalRow(report))
        audit(userId, "revenue", range, mapOf("groupBy" to groupBy, "shift" to report.shift, "rows" to rows.size))
        return CsvExport("revenue_${range.fromDate}_${range.toDate}_$groupBy.csv", CsvWriter.write(header, rows))
    }

    @Transactional
    fun topProductsCsv(userId: Long, range: ReportRange, limit: Int?, categoryId: Long?, sortBy: TopProductSort?): CsvExport {
        val report = reports.topProducts(range, limit, categoryId, sortBy)
        val header = listOf("Hạng", "Mã món", "Tên món", "Mã danh mục", "Danh mục", "Số lượng", "Doanh số", "Số Order")
        val rows = report.products.map { listOf(it.rank, it.productId, it.productName, it.categoryId, it.categoryName, it.quantity, it.sales, it.orderCount) }
        audit(userId, "top-products", range, mapOf("sortBy" to report.sortBy, "limit" to report.limit, "categoryId" to report.categoryId, "rows" to rows.size))
        return CsvExport("top-products_${range.fromDate}_${range.toDate}.csv", CsvWriter.write(header, rows))
    }

    private fun periodRow(p: RevenuePeriodResponse): List<Any?> =
        listOf(
            p.label, p.date, p.shiftCode, p.from, p.to, p.grossRevenue, p.refundedAmount, p.netRevenue, p.paymentCount, p.billCount,
            p.refundCount, p.writtenOffCount, p.writtenOffShortfall,
        ) + p.byMethod.map { it.paymentAmount }

    private fun totalRow(r: com.qrorder.report.dto.RevenueReportResponse): List<Any?> {
        val t = r.totals
        return listOf(
            "TỔNG", null, null, r.from, r.to, t.grossRevenue, t.refundedAmount, t.netRevenue, t.paymentCount, t.billCount,
            t.refundCount, t.writtenOffCount, t.writtenOffShortfall,
        ) + t.byMethod.map { it.paymentAmount }
    }

    private fun audit(userId: Long, report: String, range: ReportRange, extra: Map<String, Any?>) {
        auditService.record(
            AuditActor.User(userId),
            AuditEvent(
                action = ReportAuditActions.REPORT_EXPORTED, entityType = ReportAuditEntityTypes.REPORT, entityId = null,
                newValue = mapOf("report" to report, "format" to "CSV", "from" to range.from.toString(), "to" to range.to.toString()) + extra,
            ),
        )
    }
}
