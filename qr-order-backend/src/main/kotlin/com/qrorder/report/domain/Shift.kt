package com.qrorder.report.domain

import com.qrorder.common.config.BusinessTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Một ca làm việc theo múi giờ nghiệp vụ. Ca qua nửa đêm là ca có giờ kết thúc nhỏ hơn hoặc bằng giờ bắt đầu
 * (ví dụ 23:00 đến 06:00); ca được tính vào ngày bắt đầu ca (BE-34).
 */
data class Shift(val code: String, val name: String, val start: LocalTime, val end: LocalTime) {
    val crossesMidnight: Boolean get() = !end.isAfter(start)

    /** Các đoạn [begin, end) tính bằng phút trong ngày 0..1440 mà ca phủ lên vòng tròn 24 giờ. */
    internal fun minuteSegments(): List<IntRange> {
        val s = start.hour * 60 + start.minute
        val e = end.hour * 60 + end.minute
        return if (!crossesMidnight) listOf(s until e) else listOfNotNull(s until 1440, if (e > 0) 0 until e else null)
    }

    /** Cửa sổ [bắt đầu, kết thúc) của ca bắt đầu vào [date]. */
    fun windowOn(date: LocalDate): Pair<OffsetDateTime, OffsetDateTime> {
        val begin = date.atTime(start).atZone(BusinessTime.ZONE).toOffsetDateTime()
        val finish = (if (crossesMidnight) date.plusDays(1) else date).atTime(end).atZone(BusinessTime.ZONE).toOffsetDateTime()
        return begin to finish
    }
}
