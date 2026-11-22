package com.qrorder.report.domain

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Cấu hình báo cáo (BE-34): `app.report.*`. Giờ ca là chuỗi HH:mm để tránh phụ thuộc bộ chuyển đổi kiểu của Spring;
 * [ShiftCatalog] kiểm tra và phân tích khi khởi động nên cấu hình sai làm ứng dụng không chạy.
 */
@ConfigurationProperties(prefix = "app.report")
data class ReportProperties(
    /** Độ dài tối đa của khoảng from/to, tính bằng ngày (bảo vệ truy vấn báo cáo). */
    val maxRangeDays: Int = 366,
    val shifts: List<ShiftProperties> = emptyList(),
) {
    init {
        require(maxRangeDays in 1..3660) { "app.report.max-range-days phải từ 1 đến 3660" }
    }

    data class ShiftProperties(
        val code: String = "",
        val name: String = "",
        val start: String = "",
        val end: String = "",
    )
}
