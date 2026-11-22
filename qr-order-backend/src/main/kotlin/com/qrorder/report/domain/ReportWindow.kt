package com.qrorder.report.domain

import com.qrorder.common.config.BusinessTime
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/**
 * Một kỳ báo cáo [start, end). [date] là ngày nghiệp vụ bắt đầu kỳ (ngày, thứ Hai của tuần, mùng 1 của tháng, ngày bắt
 * đầu ca) để client không phải tự đổi múi giờ.
 */
data class ReportWindow(
    val index: Int,
    val label: String,
    val date: LocalDate,
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val shiftCode: String? = null,
    val shiftName: String? = null,
)

/**
 * Dựng các kỳ báo cáo trong [ReportRange] (múi giờ Asia/Ho_Chi_Minh). Ngày/tuần/tháng bị cắt theo khoảng truyền vào; ca
 * không bị cắt: ca được tính khi thời điểm bắt đầu ca nằm trong [from, to).
 */
object ReportWindows {
    fun build(range: ReportRange, groupBy: ReportGroupBy, shifts: List<Shift>, shiftCode: String?): List<ReportWindow> {
        if (shiftCode != null && groupBy != ReportGroupBy.SHIFT) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "Tham số shift chỉ dùng khi groupBy=SHIFT")
        }
        val windows = when (groupBy) {
            ReportGroupBy.DAY -> calendar(range, { it }, { it.plusDays(1) }, { it.toString() })
            ReportGroupBy.WEEK -> calendar(
                range, { it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }, { it.plusWeeks(1) },
                { "%d-W%02d".format(it.get(IsoFields.WEEK_BASED_YEAR), it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)) },
            )
            ReportGroupBy.MONTH -> calendar(range, { it.withDayOfMonth(1) }, { it.plusMonths(1) }, { YearMonth.from(it).toString() })
            ReportGroupBy.SHIFT -> shiftWindows(range, shifts, shiftCode)
        }
        return windows.mapIndexed { i, w -> w.copy(index = i) }
    }

    private fun calendar(
        range: ReportRange,
        periodStart: (LocalDate) -> LocalDate,
        next: (LocalDate) -> LocalDate,
        label: (LocalDate) -> String,
    ): List<ReportWindow> {
        val result = mutableListOf<ReportWindow>()
        var date = periodStart(range.fromDate)
        while (true) {
            val start = date.atStartOfDay(BusinessTime.ZONE).toOffsetDateTime()
            if (!start.isBefore(range.to)) break
            val end = next(date).atStartOfDay(BusinessTime.ZONE).toOffsetDateTime()
            result += ReportWindow(
                index = 0, label = label(date), date = date,
                start = if (start.isBefore(range.from)) range.from else start,
                end = if (end.isAfter(range.to)) range.to else end,
            )
            date = next(date)
        }
        return result
    }

    private fun shiftWindows(range: ReportRange, shifts: List<Shift>, shiftCode: String?): List<ReportWindow> {
        if (shifts.isEmpty()) throw BusinessException(ErrorCode.REPORT_SHIFT_NOT_CONFIGURED, "Chưa cấu hình ca làm việc (app.report.shifts)")
        val selected = if (shiftCode == null) shifts else shifts.filter { it.code == shiftCode }
        if (selected.isEmpty()) throw BusinessException(ErrorCode.REPORT_SHIFT_NOT_CONFIGURED, "Không có ca với mã $shiftCode")
        val result = mutableListOf<ReportWindow>()
        var date = range.fromDate
        while (date <= range.toDate) {
            for (shift in selected) {
                val (start, end) = shift.windowOn(date)
                if (!start.isBefore(range.from) && start.isBefore(range.to)) {
                    result += ReportWindow(0, "$date ${shift.code}", date, start, end, shift.code, shift.name)
                }
            }
            date = date.plusDays(1)
        }
        return result.sortedBy { it.start }
    }
}
