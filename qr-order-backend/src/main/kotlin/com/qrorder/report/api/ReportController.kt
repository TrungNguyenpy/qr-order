package com.qrorder.report.api

import com.qrorder.auth.security.CurrentPrincipal
import com.qrorder.common.api.ApiResponse
import com.qrorder.report.domain.ReportGroupBy
import com.qrorder.report.dto.RevenueReportResponse
import com.qrorder.report.dto.ServiceTimeResponse
import com.qrorder.report.dto.SummaryReportResponse
import com.qrorder.report.dto.TopProductSort
import com.qrorder.report.dto.TopProductsResponse
import com.qrorder.report.service.CsvExport
import com.qrorder.report.service.ReportExportService
import com.qrorder.report.service.ReportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val REPORT_VIEW = "@perm.has('REPORT_VIEW')"
private const val RANGE_HELP = "Ngày yyyy-MM-dd (to là ngày cuối, tính cả ngày đó) hoặc thời điểm ISO-8601 có offset (to loại trừ). Bỏ cả from và to để lấy hôm nay."
private val CSV_MEDIA_TYPE = MediaType("text", "csv", Charsets.UTF_8)

/**
 * Báo cáo (FR-RPT-01..03). MANAGER có REPORT_VIEW mặc định; ADMIN/STAFF/KITCHEN cần được cấp quyền (BR-A07), khách và
 * người chưa đăng nhập bị từ chối ở tầng bảo mật.
 */
@Tag(name = "Reports", description = "Doanh thu, tổng hợp vận hành, món bán chạy, thời gian phục vụ, xuất CSV")
@RestController
@RequestMapping("/api/v1/reports")
class ReportController(
    private val reports: ReportService,
    private val exports: ReportExportService,
) {
    @Operation(summary = "Doanh thu theo ngày/tuần/tháng/ca: gộp, hoàn tiền, ròng, theo phương thức; thất thu hiển thị riêng")
    @GetMapping("/revenue")
    @PreAuthorize(REPORT_VIEW)
    fun revenue(
        @Parameter(description = RANGE_HELP) @RequestParam(required = false) from: String?,
        @Parameter(description = RANGE_HELP) @RequestParam(required = false) to: String?,
        @RequestParam(defaultValue = "DAY") groupBy: ReportGroupBy,
        @Parameter(description = "Mã ca (chỉ với groupBy=SHIFT)") @RequestParam(required = false) shift: String?,
    ): ApiResponse<RevenueReportResponse> = ApiResponse.ok(reports.revenue(reports.resolveRange(from, to), groupBy, shift))

    @Operation(summary = "Tổng hợp vận hành: thất thu, hoàn tiền, tỷ lệ hủy/từ chối, món wasted, phiên và đợt order, hàng cần xử lý")
    @GetMapping("/summary")
    @PreAuthorize(REPORT_VIEW)
    fun summary(
        @Parameter(description = RANGE_HELP) @RequestParam(required = false) from: String?,
        @Parameter(description = RANGE_HELP) @RequestParam(required = false) to: String?,
    ): ApiResponse<SummaryReportResponse> = ApiResponse.ok(reports.summary(reports.resolveRange(from, to)))

    @Operation(summary = "Món bán chạy (món tính tiền của Order PAID theo ngày thanh toán, doanh số = line_total)")
    @GetMapping("/top-products")
    @PreAuthorize(REPORT_VIEW)
    fun topProducts(
        @Parameter(description = RANGE_HELP) @RequestParam(required = false) from: String?,
        @Parameter(description = RANGE_HELP) @RequestParam(required = false) to: String?,
        @Parameter(description = "1..100, mặc định 10") @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(required = false) sortBy: TopProductSort?,
    ): ApiResponse<TopProductsResponse> =
        ApiResponse.ok(reports.topProducts(reports.resolveRange(from, to), limit, categoryId, sortBy))

    @Operation(summary = "Thời gian phục vụ: chờ xác nhận, chờ bếp, nấu, mang ra, tổng (trung bình và trung vị, giây)")
    @GetMapping("/service-time")
    @PreAuthorize(REPORT_VIEW)
    fun serviceTime(
        @Parameter(description = RANGE_HELP) @RequestParam(required = false) from: String?,
        @Parameter(description = RANGE_HELP) @RequestParam(required = false) to: String?,
    ): ApiResponse<ServiceTimeResponse> = ApiResponse.ok(reports.serviceTime(reports.resolveRange(from, to)))

    @Operation(summary = "Xuất doanh thu CSV (UTF-8 có BOM, ghi audit REPORT_EXPORTED)")
    @GetMapping("/revenue.csv")
    @PreAuthorize(REPORT_VIEW)
    fun revenueCsv(
        @Parameter(description = RANGE_HELP) @RequestParam(required = false) from: String?,
        @Parameter(description = RANGE_HELP) @RequestParam(required = false) to: String?,
        @RequestParam(defaultValue = "DAY") groupBy: ReportGroupBy,
        @RequestParam(required = false) shift: String?,
    ): ResponseEntity<ByteArray> =
        csv(exports.revenueCsv(CurrentPrincipal.requireStaff().userId, reports.resolveRange(from, to), groupBy, shift?.takeIf { it.isNotBlank() }))

    @Operation(summary = "Xuất món bán chạy CSV (UTF-8 có BOM, ghi audit REPORT_EXPORTED)")
    @GetMapping("/top-products.csv")
    @PreAuthorize(REPORT_VIEW)
    fun topProductsCsv(
        @Parameter(description = RANGE_HELP) @RequestParam(required = false) from: String?,
        @Parameter(description = RANGE_HELP) @RequestParam(required = false) to: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(required = false) sortBy: TopProductSort?,
    ): ResponseEntity<ByteArray> =
        csv(exports.topProductsCsv(CurrentPrincipal.requireStaff().userId, reports.resolveRange(from, to), limit, categoryId, sortBy))

    /** Tệp tải về, không cache (dữ liệu tài chính). */
    private fun csv(export: CsvExport): ResponseEntity<ByteArray> {
        val disposition = ContentDisposition.attachment().filename(export.fileName).build()
        return ResponseEntity.ok()
            .contentType(CSV_MEDIA_TYPE)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .body(export.content)
    }
}
