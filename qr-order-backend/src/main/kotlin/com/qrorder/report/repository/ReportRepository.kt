package com.qrorder.report.repository

import com.qrorder.billing.domain.PaymentMethod
import com.qrorder.report.domain.ReportWindow
import com.qrorder.report.dto.TopProductSort
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

// Truy vấn báo cáo (chỉ đọc). Các kỳ được truyền bằng một tham số JSON và nối với bảng nguồn để mọi cách nhóm (ngày, tuần,
// tháng, ca không liền kề) dùng chung cùng một câu lệnh và vẫn quét theo chỉ mục (confirmed_at, written_off_at).
// Để internal để test EXPLAIN kiểm tra kế hoạch thực thi.

private const val WINDOWS = "jsonb_to_recordset(CAST(:windows AS jsonb)) AS w(idx integer, s timestamptz, e timestamptz)"

internal const val SQL_PAYMENTS_BY_METHOD = """
    SELECT w.idx AS idx, p.method AS method, count(*) AS cnt, COALESCE(sum(p.amount), 0) AS amount
    FROM $WINDOWS
    JOIN payment_transaction p ON p.status = 'SUCCESS' AND p.confirmed_at >= w.s AND p.confirmed_at < w.e
    GROUP BY w.idx, p.method
"""

internal const val SQL_PAYMENT_BILLS_BY_WINDOW = """
    SELECT w.idx AS idx, count(DISTINCT p.bill_id) AS cnt
    FROM $WINDOWS
    JOIN payment_transaction p ON p.status = 'SUCCESS' AND p.confirmed_at >= w.s AND p.confirmed_at < w.e
    GROUP BY w.idx
"""

internal const val SQL_PAYMENT_BILLS_TOTAL = """
    SELECT count(DISTINCT p.bill_id) AS cnt
    FROM $WINDOWS
    JOIN payment_transaction p ON p.status = 'SUCCESS' AND p.confirmed_at >= w.s AND p.confirmed_at < w.e
"""

internal const val SQL_REFUNDS_BY_METHOD = """
    SELECT w.idx AS idx, r.method AS method, count(*) AS cnt, COALESCE(sum(r.amount), 0) AS amount
    FROM $WINDOWS
    JOIN refund_transaction r ON r.status = 'SUCCESS' AND r.confirmed_at >= w.s AND r.confirmed_at < w.e
    GROUP BY w.idx, r.method
"""

internal const val SQL_WRITE_OFFS_BY_WINDOW = """
    SELECT w.idx AS idx, count(*) AS cnt, COALESCE(sum(o.shortfall_amount), 0) AS amount
    FROM $WINDOWS
    JOIN orders o ON o.status = 'WRITTEN_OFF' AND o.written_off_at >= w.s AND o.written_off_at < w.e
    GROUP BY w.idx
"""

internal const val SQL_ITEM_COHORT = """
    SELECT count(*) AS total,
           count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
           count(*) FILTER (WHERE status = 'REJECTED') AS rejected,
           count(*) FILTER (WHERE wasted) AS wasted,
           COALESCE(sum(line_total) FILTER (WHERE wasted), 0) AS wasted_value
    FROM order_item
    WHERE created_at >= :from AND created_at < :to
"""

internal const val SQL_ITEM_REASONS = """
    SELECT reason_code, count(*) AS cnt
    FROM order_item
    WHERE created_at >= :from AND created_at < :to AND status IN ('CANCELLED', 'REJECTED')
    GROUP BY reason_code
    ORDER BY cnt DESC, reason_code ASC
"""

internal const val SQL_SESSIONS_OPENED = "SELECT count(*) FROM table_session WHERE opened_at >= :from AND opened_at < :to"

internal const val SQL_BATCHES = """
    SELECT count(*) AS total, count(*) FILTER (WHERE submitted_by_type = 'STAFF') AS by_staff
    FROM order_batch
    WHERE submitted_at >= :from AND submitted_at < :to
"""

internal const val SQL_ORDERS_NEEDING_ATTENTION = "SELECT count(*) FROM orders WHERE needs_attention"

internal const val SQL_BILLS_VOID_PENDING = "SELECT count(*) FROM bill WHERE status = 'VOID_PENDING'"

internal const val SQL_SERVICE_TIME = """
    SELECT count(*) AS served,
           count(*) FILTER (WHERE b.requires_confirmation) AS confirmation_n,
           avg(GREATEST(0, extract(epoch FROM (i.confirmed_at - b.submitted_at)))) FILTER (WHERE b.requires_confirmation) AS confirmation_avg,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY GREATEST(0, extract(epoch FROM (i.confirmed_at - b.submitted_at))))
               FILTER (WHERE b.requires_confirmation) AS confirmation_median,
           avg(GREATEST(0, extract(epoch FROM (i.preparing_at - i.confirmed_at)))) AS queue_avg,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY GREATEST(0, extract(epoch FROM (i.preparing_at - i.confirmed_at)))) AS queue_median,
           avg(GREATEST(0, extract(epoch FROM (i.ready_at - i.preparing_at)))) AS cooking_avg,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY GREATEST(0, extract(epoch FROM (i.ready_at - i.preparing_at)))) AS cooking_median,
           avg(GREATEST(0, extract(epoch FROM (i.served_at - i.ready_at)))) AS delivery_avg,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY GREATEST(0, extract(epoch FROM (i.served_at - i.ready_at)))) AS delivery_median,
           avg(GREATEST(0, extract(epoch FROM (i.served_at - b.submitted_at)))) AS total_avg,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY GREATEST(0, extract(epoch FROM (i.served_at - b.submitted_at)))) AS total_median
    FROM order_item i
    JOIN order_batch b ON b.id = i.batch_id
    WHERE i.status = 'SERVED' AND i.served_at >= :from AND i.served_at < :to
"""

/** Giá trị gộp theo kỳ và phương thức. */
data class MethodRow(val windowIndex: Int, val method: PaymentMethod, val count: Int, val amount: Long)

data class WindowCountRow(val windowIndex: Int, val count: Int, val amount: Long)

data class ItemCohortRow(val total: Int, val cancelled: Int, val rejected: Int, val wasted: Int, val wastedValue: Long)

data class ReasonRow(val reasonCode: String, val count: Int)

data class BatchCountRow(val total: Int, val byStaff: Int)

data class TopProductRow(
    val productId: Long,
    val productName: String,
    val categoryId: Long,
    val categoryName: String,
    val quantity: Long,
    val sales: Long,
    val orderCount: Int,
)

/** Trung bình và trung vị (giây) của một chặng; null khi không có mẫu. */
data class SegmentStats(val sampleCount: Int, val average: BigDecimal?, val median: BigDecimal?)

data class ServiceTimeRow(
    val servedItems: Int,
    val confirmation: SegmentStats,
    val kitchenQueue: SegmentStats,
    val cooking: SegmentStats,
    val delivery: SegmentStats,
    val total: SegmentStats,
)

/**
 * Truy vấn báo cáo trực tiếp bằng JDBC (chỉ đọc, không qua JPA): kết quả là số liệu gộp nên không cần entity, và câu lệnh
 * SQL viết rõ để dùng đúng các chỉ mục báo cáo của V1 (NFR-01).
 */
@Repository
class ReportRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun paymentsByMethod(windows: List<ReportWindow>): List<MethodRow> =
        jdbc.query(SQL_PAYMENTS_BY_METHOD, windowParams(windows)) { rs, _ -> methodRow(rs) }

    fun refundsByMethod(windows: List<ReportWindow>): List<MethodRow> =
        jdbc.query(SQL_REFUNDS_BY_METHOD, windowParams(windows)) { rs, _ -> methodRow(rs) }

    /** Số Bill khác nhau có Payment SUCCESS trong từng kỳ. */
    fun paymentBillsByWindow(windows: List<ReportWindow>): Map<Int, Int> =
        jdbc.query(SQL_PAYMENT_BILLS_BY_WINDOW, windowParams(windows)) { rs, _ -> rs.getInt("idx") to rs.getInt("cnt") }.toMap()

    /** Số Bill khác nhau có Payment SUCCESS trong toàn bộ các kỳ. */
    fun paymentBillsTotal(windows: List<ReportWindow>): Int =
        jdbc.queryForObject(SQL_PAYMENT_BILLS_TOTAL, windowParams(windows), Int::class.javaObjectType) ?: 0

    fun writeOffsByWindow(windows: List<ReportWindow>): Map<Int, WindowCountRow> =
        jdbc.query(SQL_WRITE_OFFS_BY_WINDOW, windowParams(windows)) { rs, _ ->
            WindowCountRow(rs.getInt("idx"), rs.getInt("cnt"), rs.getLong("amount"))
        }.associateBy { it.windowIndex }

    fun itemCohort(from: OffsetDateTime, to: OffsetDateTime): ItemCohortRow =
        jdbc.query(SQL_ITEM_COHORT, rangeParams(from, to)) { rs, _ ->
            ItemCohortRow(rs.getInt("total"), rs.getInt("cancelled"), rs.getInt("rejected"), rs.getInt("wasted"), rs.getLong("wasted_value"))
        }.single()

    fun itemReasons(from: OffsetDateTime, to: OffsetDateTime): List<ReasonRow> =
        jdbc.query(SQL_ITEM_REASONS, rangeParams(from, to)) { rs, _ -> ReasonRow(rs.getString("reason_code"), rs.getInt("cnt")) }

    fun sessionsOpened(from: OffsetDateTime, to: OffsetDateTime): Int =
        jdbc.queryForObject(SQL_SESSIONS_OPENED, rangeParams(from, to), Int::class.javaObjectType) ?: 0

    fun batches(from: OffsetDateTime, to: OffsetDateTime): BatchCountRow =
        jdbc.query(SQL_BATCHES, rangeParams(from, to)) { rs, _ -> BatchCountRow(rs.getInt("total"), rs.getInt("by_staff")) }.single()

    fun ordersNeedingAttention(): Int = jdbc.queryForObject(SQL_ORDERS_NEEDING_ATTENTION, MapSqlParameterSource(), Int::class.javaObjectType) ?: 0

    fun billsVoidPending(): Int = jdbc.queryForObject(SQL_BILLS_VOID_PENDING, MapSqlParameterSource(), Int::class.javaObjectType) ?: 0

    /**
     * Món bán chạy (BE-36): các món tính tiền (CONFIRMED, PREPARING, READY, SERVED) của Order PAID theo ngày thanh toán
     * `orders.paid_at`; doanh số là tổng `line_total`. ORDER BY chọn từ danh sách cố định theo enum (không nối chuỗi từ client).
     */
    fun topProducts(from: OffsetDateTime, to: OffsetDateTime, categoryId: Long?, sortBy: TopProductSort, limit: Int): List<TopProductRow> {
        val orderBy = when (sortBy) {
            TopProductSort.QUANTITY -> "quantity DESC, sales DESC"
            TopProductSort.REVENUE -> "sales DESC, quantity DESC"
        }
        val categoryFilter = if (categoryId != null) "AND p.category_id = :categoryId" else ""
        val sql = """
            SELECT p.id AS product_id, p.name AS product_name, c.id AS category_id, c.name AS category_name,
                   sum(i.quantity) AS quantity, sum(i.line_total) AS sales, count(DISTINCT o.id) AS order_count
            FROM orders o
            JOIN order_item i ON i.order_id = o.id AND i.status IN ('CONFIRMED', 'PREPARING', 'READY', 'SERVED')
            JOIN product p ON p.id = i.product_id
            JOIN category c ON c.id = p.category_id
            WHERE o.status = 'PAID' AND o.paid_at >= :from AND o.paid_at < :to $categoryFilter
            GROUP BY p.id, p.name, c.id, c.name
            ORDER BY $orderBy, p.name ASC, p.id ASC
            LIMIT :limit
        """
        val params = rangeParams(from, to).addValue("limit", limit)
        if (categoryId != null) params.addValue("categoryId", categoryId)
        return jdbc.query(sql, params) { rs, _ ->
            TopProductRow(
                rs.getLong("product_id"), rs.getString("product_name"), rs.getLong("category_id"), rs.getString("category_name"),
                rs.getLong("quantity"), rs.getLong("sales"), rs.getInt("order_count"),
            )
        }
    }

    fun serviceTime(from: OffsetDateTime, to: OffsetDateTime): ServiceTimeRow =
        jdbc.query(SQL_SERVICE_TIME, rangeParams(from, to)) { rs, _ ->
            ServiceTimeRow(
                servedItems = rs.getInt("served"),
                confirmation = stats(rs, "confirmation_n", "confirmation_avg", "confirmation_median"),
                kitchenQueue = stats(rs, "served", "queue_avg", "queue_median"),
                cooking = stats(rs, "served", "cooking_avg", "cooking_median"),
                delivery = stats(rs, "served", "delivery_avg", "delivery_median"),
                total = stats(rs, "served", "total_avg", "total_median"),
            )
        }.single()

    // ------------------------------------------------------------------------------------------ Nội bộ

    private fun methodRow(rs: ResultSet) =
        MethodRow(rs.getInt("idx"), PaymentMethod.valueOf(rs.getString("method")), rs.getInt("cnt"), rs.getLong("amount"))

    private fun stats(rs: ResultSet, countColumn: String, avgColumn: String, medianColumn: String): SegmentStats =
        SegmentStats(rs.getInt(countColumn), seconds(rs.getBigDecimal(avgColumn)), seconds(rs.getBigDecimal(medianColumn)))

    private fun seconds(value: BigDecimal?): BigDecimal? = value?.setScale(1, java.math.RoundingMode.HALF_UP)

    private fun rangeParams(from: OffsetDateTime, to: OffsetDateTime) =
        MapSqlParameterSource().addValue("from", from).addValue("to", to)

    private fun windowParams(windows: List<ReportWindow>) = MapSqlParameterSource().addValue("windows", windowsJson(windows))

    companion object {
        /** JSON các kỳ [{idx, s, e}]; chỉ chứa số và chuỗi ISO nên không cần thư viện JSON. */
        internal fun windowsJson(windows: List<ReportWindow>): String = windows.joinToString(prefix = "[", postfix = "]", separator = ",") {
            """{"idx":${it.index},"s":"${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(it.start)}","e":"${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(it.end)}"}"""
        }
    }
}
