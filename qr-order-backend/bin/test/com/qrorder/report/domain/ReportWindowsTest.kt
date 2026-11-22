package com.qrorder.report.domain

import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** BE-34: dựng các kỳ báo cáo theo ngày, tuần ISO, tháng và ca (Asia/Ho_Chi_Minh). */
class ReportWindowsTest {

    private val shifts = ShiftCatalog.parse(
        listOf(
            ReportProperties.ShiftProperties("MORNING", "Ca sáng", "06:00", "14:00"),
            ReportProperties.ShiftProperties("EVENING", "Ca chiều tối", "14:00", "23:00"),
            ReportProperties.ShiftProperties("NIGHT", "Ca đêm", "23:00", "06:00"),
        ),
    )

    private fun range(from: String, to: String) = ReportRange.resolve(from, to, LocalDate.of(2026, 11, 22), 366)

    private fun build(from: String, to: String, groupBy: ReportGroupBy, shift: String? = null) =
        ReportWindows.build(range(from, to), groupBy, shifts, shift)

    @Test
    fun `day windows cover each business day and are contiguous`() {
        val w = build("2026-11-20", "2026-11-22", ReportGroupBy.DAY)
        assertEquals(listOf("2026-11-20", "2026-11-21", "2026-11-22"), w.map { it.label })
        assertEquals(listOf(0, 1, 2), w.map { it.index })
        assertEquals("2026-11-20T00:00+07:00", w[0].start.toString())
        assertEquals("2026-11-21T00:00+07:00", w[0].end.toString())
        assertEquals(w[0].end, w[1].start)
        assertEquals("2026-11-23T00:00+07:00", w[2].end.toString())
    }

    @Test
    fun `day windows are clipped by a date time range`() {
        val w = build("2026-11-21T10:00:00+07:00", "2026-11-22T12:00:00+07:00", ReportGroupBy.DAY)
        assertEquals(2, w.size)
        assertEquals("2026-11-21T10:00+07:00", w[0].start.toString())
        assertEquals("2026-11-22T00:00+07:00", w[0].end.toString())
        assertEquals("2026-11-22T12:00+07:00", w[1].end.toString())
    }

    @Test
    fun `week windows start on Monday are labelled with the ISO week and are clipped`() {
        val w = build("2026-11-12", "2026-11-17", ReportGroupBy.WEEK)
        assertEquals(listOf("2026-W46", "2026-W47"), w.map { it.label })
        assertEquals(LocalDate.of(2026, 11, 9), w[0].date)
        assertEquals("2026-11-12T00:00+07:00", w[0].start.toString(), "tuần đầu bị cắt theo from")
        assertEquals("2026-11-16T00:00+07:00", w[0].end.toString())
        assertEquals("2026-11-16T00:00+07:00", w[1].start.toString())
        assertEquals("2026-11-18T00:00+07:00", w[1].end.toString(), "tuần cuối bị cắt theo to")
    }

    @Test
    fun `week labels use the ISO week based year around new year`() {
        val w = build("2026-12-28", "2027-01-03", ReportGroupBy.WEEK)
        assertEquals(listOf("2026-W53"), w.map { it.label })
    }

    @Test
    fun `month windows are labelled yyyy-MM and clipped`() {
        val w = build("2026-11-15", "2027-01-10", ReportGroupBy.MONTH)
        assertEquals(listOf("2026-11", "2026-12", "2027-01"), w.map { it.label })
        assertEquals("2026-11-15T00:00+07:00", w[0].start.toString())
        assertEquals("2026-12-01T00:00+07:00", w[0].end.toString())
        assertEquals(LocalDate.of(2026, 12, 1), w[1].date)
        assertEquals("2027-01-11T00:00+07:00", w[2].end.toString())
    }

    @Test
    fun `shift windows follow the configured order and the night shift ends the next morning`() {
        val w = build("2026-11-21", "2026-11-21", ReportGroupBy.SHIFT)
        assertEquals(listOf("MORNING", "EVENING", "NIGHT"), w.map { it.shiftCode })
        assertEquals("2026-11-21T06:00+07:00", w[0].start.toString())
        assertEquals("2026-11-21T14:00+07:00", w[0].end.toString())
        assertEquals("2026-11-21T23:00+07:00", w[2].start.toString())
        assertEquals("2026-11-22T06:00+07:00", w[2].end.toString(), "ca đêm tính vào ngày bắt đầu 2026-11-21")
        assertEquals("2026-11-21 NIGHT", w[2].label)
        assertEquals("Ca đêm", w[2].shiftName)
    }

    @Test
    fun `a night shift that started before the range is not part of it`() {
        val w = build("2026-11-22", "2026-11-22", ReportGroupBy.SHIFT)
        assertEquals(listOf("2026-11-22 MORNING", "2026-11-22 EVENING", "2026-11-22 NIGHT"), w.map { it.label })
    }

    @Test
    fun `shift filter keeps only that shift for every day`() {
        val w = build("2026-11-21", "2026-11-23", ReportGroupBy.SHIFT, "NIGHT")
        assertEquals(listOf("2026-11-21 NIGHT", "2026-11-22 NIGHT", "2026-11-23 NIGHT"), w.map { it.label })
    }

    @Test
    fun `shift windows are sorted by start time on a date time range`() {
        val w = build("2026-11-21T13:00:00+07:00", "2026-11-22T07:00:00+07:00", ReportGroupBy.SHIFT)
        assertEquals(listOf("2026-11-21 EVENING", "2026-11-21 NIGHT", "2026-11-22 MORNING"), w.map { it.label })
        assertTrue(w.zipWithNext().all { (a, b) -> a.start.isBefore(b.start) })
    }

    @Test
    fun `unknown shift code is rejected`() {
        val ex = assertFailsWith<BusinessException> { build("2026-11-21", "2026-11-21", ReportGroupBy.SHIFT, "AFTERNOON") }
        assertEquals(ErrorCode.REPORT_SHIFT_NOT_CONFIGURED, ex.errorCode)
    }

    @Test
    fun `grouping by shift without configured shifts is rejected`() {
        val ex = assertFailsWith<BusinessException> {
            ReportWindows.build(range("2026-11-21", "2026-11-21"), ReportGroupBy.SHIFT, emptyList(), null)
        }
        assertEquals(ErrorCode.REPORT_SHIFT_NOT_CONFIGURED, ex.errorCode)
    }

    @Test
    fun `shift parameter with another grouping is rejected`() {
        val ex = assertFailsWith<BusinessException> { build("2026-11-21", "2026-11-21", ReportGroupBy.DAY, "NIGHT") }
        assertEquals(ErrorCode.VALIDATION_FAILED, ex.errorCode)
    }
}
