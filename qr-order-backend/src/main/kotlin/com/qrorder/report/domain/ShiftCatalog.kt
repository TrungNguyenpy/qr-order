package com.qrorder.report.domain

import org.springframework.stereotype.Component
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * Danh sách ca đã kiểm tra: mã UPPER_SNAKE_CASE duy nhất, giờ HH:mm hợp lệ, giờ bắt đầu khác giờ kết thúc và các ca
 * không chồng lấn nhau trên vòng tròn 24 giờ (kể cả ca qua nửa đêm). Vi phạm ném [IllegalArgumentException] khi khởi động.
 */
@Component
class ShiftCatalog(properties: ReportProperties) {
    val shifts: List<Shift> = parse(properties.shifts)

    fun find(code: String): Shift? = shifts.firstOrNull { it.code == code }

    companion object {
        private val CODE = Regex("^[A-Z][A-Z0-9_]{0,31}$")
        private val TIME = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

        fun parse(configured: List<ReportProperties.ShiftProperties>): List<Shift> {
            val shifts = configured.mapIndexed { index, c -> toShift(index, c) }
            val duplicate = shifts.groupBy { it.code }.entries.firstOrNull { it.value.size > 1 }
            require(duplicate == null) { "app.report.shifts: mã ca trùng ${duplicate?.key}" }
            for (i in shifts.indices) {
                for (j in i + 1 until shifts.size) {
                    require(!overlaps(shifts[i], shifts[j])) { "app.report.shifts: ca ${shifts[i].code} chồng lấn với ca ${shifts[j].code}" }
                }
            }
            return shifts
        }

        private fun toShift(index: Int, c: ReportProperties.ShiftProperties): Shift {
            val where = "app.report.shifts[$index]"
            require(CODE.matches(c.code)) { "$where.code phải là UPPER_SNAKE_CASE (tối đa 32 ký tự), nhận '${c.code}'" }
            require(c.name.isNotBlank()) { "$where.name không được để trống" }
            val start = parseTime("$where.start", c.start)
            val end = parseTime("$where.end", c.end)
            require(start != end) { "$where: giờ bắt đầu và kết thúc không được trùng nhau" }
            return Shift(c.code, c.name.trim(), start, end)
        }

        private fun parseTime(field: String, value: String): LocalTime {
            require(TIME.matches(value)) { "$field phải có dạng HH:mm, nhận '$value'" }
            return try {
                LocalTime.parse(value)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("$field không hợp lệ: '$value'", e)
            }
        }

        internal fun overlaps(a: Shift, b: Shift): Boolean =
            a.minuteSegments().any { x -> b.minuteSegments().any { y -> x.first < y.last + 1 && y.first < x.last + 1 } }
    }
}
