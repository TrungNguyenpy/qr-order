package com.qrorder.report.dto

import com.qrorder.billing.domain.PaymentMethod
import com.qrorder.report.domain.ReportGroupBy
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/** Tiền thu và hoàn theo một phương thức thanh toán. Số tiền là VND (số nguyên). */
data class MethodAmountResponse(
    val method: PaymentMethod,
    val paymentCount: Int,
    val paymentAmount: Long,
    val refundCount: Int,
    val refundAmount: Long,
    val netAmount: Long,
)

/**
 * Một kỳ doanh thu (BE-35). Doanh thu gộp = tổng Payment SUCCESS theo ngày xác nhận; hoàn tiền = tổng Refund SUCCESS
 * theo ngày xác nhận; doanh thu ròng = gộp trừ hoàn. Thất thu (write-off) hiển thị riêng, không cộng/trừ vào doanh thu.
 */
data class RevenuePeriodResponse(
    val label: String,
    /** Ngày nghiệp vụ (Asia/Ho_Chi_Minh) bắt đầu kỳ. */
    val date: LocalDate,
    val from: OffsetDateTime,
    val to: OffsetDateTime,
    val shiftCode: String?,
    val shiftName: String?,
    val grossRevenue: Long,
    val refundedAmount: Long,
    val netRevenue: Long,
    val paymentCount: Int,
    val billCount: Int,
    val refundCount: Int,
    val writtenOffCount: Int,
    val writtenOffShortfall: Long,
    val byMethod: List<MethodAmountResponse>,
)

data class RevenueTotalsResponse(
    val grossRevenue: Long,
    val refundedAmount: Long,
    val netRevenue: Long,
    val paymentCount: Int,
    /** Số Bill khác nhau có Payment SUCCESS trong khoảng (một Bill thanh toán nhiều lần chỉ tính một). */
    val billCount: Int,
    val refundCount: Int,
    val writtenOffCount: Int,
    val writtenOffShortfall: Long,
    val byMethod: List<MethodAmountResponse>,
)

data class RevenueReportResponse(
    val from: OffsetDateTime,
    val to: OffsetDateTime,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val groupBy: ReportGroupBy,
    val shift: String?,
    val totals: RevenueTotalsResponse,
    val periods: List<RevenuePeriodResponse>,
)

data class ReasonCountResponse(val reasonCode: String, val count: Int)

data class ItemSummaryResponse(
    val totalItems: Int,
    val cancelledItems: Int,
    val rejectedItems: Int,
    /** Tỷ lệ trên tổng món tạo trong khoảng (0..1, 4 chữ số thập phân). */
    val cancelRate: BigDecimal,
    val rejectRate: BigDecimal,
    val wastedItems: Int,
    val wastedValue: Long,
    val byReason: List<ReasonCountResponse>,
)

data class SummaryReportResponse(
    val from: OffsetDateTime,
    val to: OffsetDateTime,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val writtenOffCount: Int,
    val writtenOffShortfall: Long,
    val refundCount: Int,
    val refundedAmount: Long,
    val sessionsOpened: Int,
    val batchesSubmitted: Int,
    val batchesByStaff: Int,
    val items: ItemSummaryResponse,
    /** Ảnh chụp hiện tại (không phụ thuộc khoảng): Order cần xử lý và Bill chờ hoàn tiền. */
    val ordersNeedingAttention: Int,
    val billsVoidPending: Int,
)

enum class TopProductSort { QUANTITY, REVENUE }

data class TopProductResponse(
    val rank: Int,
    val productId: Long,
    val productName: String,
    val categoryId: Long,
    val categoryName: String,
    val quantity: Long,
    val sales: Long,
    val orderCount: Int,
)

data class TopProductsResponse(
    val from: OffsetDateTime,
    val to: OffsetDateTime,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val sortBy: TopProductSort,
    val categoryId: Long?,
    val limit: Int,
    val products: List<TopProductResponse>,
)

/** Một chặng phục vụ; thời gian tính bằng giây, null khi không có mẫu. */
data class ServiceSegmentResponse(
    val segment: String,
    val sampleCount: Int,
    val averageSeconds: BigDecimal?,
    val medianSeconds: BigDecimal?,
)

data class ServiceTimeResponse(
    val from: OffsetDateTime,
    val to: OffsetDateTime,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val servedItems: Int,
    val segments: List<ServiceSegmentResponse>,
)
