package com.qrorder.common.time

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Thời điểm hiện tại theo đồng hồ của PostgreSQL (`now()` = thời điểm bắt đầu transaction hiện tại).
 *
 * BE-03: các cột thời điểm do ứng dụng ghi nhưng bị CHECK so sánh với cột do DB đặt bằng `now()`
 * (ví dụ `table_session.closed_at >= opened_at`) phải lấy từ cùng đồng hồ DB, tránh lệch giờ giữa máy ứng dụng
 * và máy DB làm vi phạm CHECK. JdbcTemplate dùng chung connection của transaction JPA đang chạy (JpaTransactionManager
 * expose JDBC connection) nên giá trị nhất quán với các câu lệnh khác trong cùng transaction.
 */
@Component
class DbClock(private val jdbc: JdbcTemplate) {
    fun now(): OffsetDateTime =
        requireNotNull(jdbc.queryForObject("SELECT now()", OffsetDateTime::class.java)) { "now() trả về null" }
}
