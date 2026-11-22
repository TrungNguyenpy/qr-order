package com.qrorder.report.domain

import com.qrorder.common.config.BusinessTime
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Khoảng thời gian báo cáo [from, to) (BE-34). `to` là cận trên loại trừ.
 *
 * Tham số `from`/`to` nhận ngày `yyyy-MM-dd` (theo múi giờ nghiệp vụ; `to` là ngày cuối được tính, nên cận trên là
 * đầu ngày kế tiếp) hoặc thời điểm ISO-8601 có offset (cận dưới bao gồm, cận trên loại trừ). Không truyền cả hai thì
 * lấy hôm nay. Truyền thiếu một đầu, sai định dạng, `from >= to` hoặc vượt số ngày tối đa trả INVALID_REPORT_RANGE.
 */
data class ReportRange(val from: OffsetDateTime, val to: OffsetDateTime) {
    /** Ngày nghiệp vụ đầu tiên được tính. */
    val fromDate: LocalDate get() = from.atZoneSameInstant(BusinessTime.ZONE).toLocalDate()

    /** Ngày nghiệp vụ cuối cùng được tính (to là loại trừ nên lùi 1 nano giây). */
    val toDate: LocalDate get() = to.minusNanos(1).atZoneSameInstant(BusinessTime.ZONE).toLocalDate()

    companion object {
        fun resolve(from: String?, to: String?, today: LocalDate, maxRangeDays: Int): ReportRange {
            val rawFrom = from?.takeIf { it.isNotBlank() }
            val rawTo = to?.takeIf { it.isNotBlank() }
            if ((rawFrom == null) != (rawTo == null)) {
                throw invalid("Phải truyền cả from và to, hoặc bỏ cả hai để lấy hôm nay")
            }
            val start: OffsetDateTime
            val end: OffsetDateTime
            if (rawFrom == null || rawTo == null) {
                start = startOfDay(today)
                end = startOfDay(today.plusDays(1))
            } else {
                start = parse("from", rawFrom, endOfDay = false)
                end = parse("to", rawTo, endOfDay = true)
            }
            if (!start.isBefore(end)) throw invalid("from phải nhỏ hơn to")
            if (Duration.between(start, end).toMinutes() > maxRangeDays * 1440L) {
                throw invalid("Khoảng báo cáo tối đa $maxRangeDays ngày")
            }
            return ReportRange(start, end)
        }

        private fun parse(name: String, raw: String, endOfDay: Boolean): OffsetDateTime {
            // Dấu "+" của offset trong query string bị giải mã thành khoảng trắng nếu client không mã hóa %2B.
            val value = raw.trim().replace(' ', '+')
            try {
                if (!value.contains('T')) {
                    val date = LocalDate.parse(value)
                    return startOfDay(if (endOfDay) date.plusDays(1) else date)
                }
                return OffsetDateTime.parse(value)
            } catch (e: DateTimeParseException) {
                throw invalid("$name phải là ngày yyyy-MM-dd hoặc thời điểm ISO-8601 có offset, nhận '$raw'")
            }
        }

        private fun startOfDay(date: LocalDate): OffsetDateTime = date.atStartOfDay(BusinessTime.ZONE).toOffsetDateTime()

        private fun invalid(message: String) = BusinessException(ErrorCode.INVALID_REPORT_RANGE, message)
    }
}
