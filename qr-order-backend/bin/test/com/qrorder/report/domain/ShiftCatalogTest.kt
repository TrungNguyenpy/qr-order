package com.qrorder.report.domain

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BE-34: phân tích và kiểm tra cấu hình ca làm việc (app.report.shifts). */
class ShiftCatalogTest {

    private fun shift(code: String, start: String, end: String, name: String = "Ca $code") =
        ReportProperties.ShiftProperties(code, name, start, end)

    private fun message(vararg shifts: ReportProperties.ShiftProperties): String =
        assertFailsWith<IllegalArgumentException> { ShiftCatalog.parse(shifts.toList()) }.message.orEmpty()

    @Test
    fun `three consecutive shifts including one crossing midnight are accepted`() {
        val shifts = ShiftCatalog.parse(
            listOf(shift("MORNING", "06:00", "14:00"), shift("EVENING", "14:00", "23:00"), shift("NIGHT", "23:00", "06:00")),
        )
        assertEquals(listOf("MORNING", "EVENING", "NIGHT"), shifts.map { it.code })
        assertEquals(LocalTime.of(6, 0), shifts[0].start)
        assertFalse(shifts[0].crossesMidnight)
        assertTrue(shifts[2].crossesMidnight)
    }

    @Test
    fun `an empty list is valid and means no shift grouping`() {
        assertTrue(ShiftCatalog.parse(emptyList()).isEmpty())
    }

    @Test
    fun `end at midnight counts as crossing midnight and ends at the start of the next day`() {
        val s = ShiftCatalog.parse(listOf(shift("LATE", "18:00", "00:00"))).single()
        assertTrue(s.crossesMidnight)
        val (begin, finish) = s.windowOn(java.time.LocalDate.of(2026, 11, 21))
        assertEquals("2026-11-21T18:00+07:00", begin.toString())
        assertEquals("2026-11-22T00:00+07:00", finish.toString())
    }

    @Test
    fun `duplicate codes are rejected`() {
        assertTrue(message(shift("A", "06:00", "10:00"), shift("A", "12:00", "14:00")).contains("trùng"))
    }

    @Test
    fun `lower case or blank code is rejected`() {
        assertTrue(message(shift("morning", "06:00", "10:00")).contains("code"))
        assertTrue(message(shift("", "06:00", "10:00")).contains("code"))
    }

    @Test
    fun `blank name is rejected`() {
        assertTrue(message(shift("A", "06:00", "10:00", name = " ")).contains("name"))
    }

    @Test
    fun `malformed times are rejected`() {
        assertTrue(message(shift("A", "6:00", "10:00")).contains("start"))
        assertTrue(message(shift("A", "06:00", "24:00")).contains("end"))
        assertTrue(message(shift("A", "06:60", "10:00")).contains("start"))
        assertTrue(message(shift("A", "", "10:00")).contains("start"))
    }

    @Test
    fun `same start and end is rejected`() {
        assertTrue(message(shift("A", "06:00", "06:00")).contains("không được trùng"))
    }

    @Test
    fun `overlapping shifts on the same day are rejected`() {
        assertTrue(message(shift("A", "06:00", "14:00"), shift("B", "13:00", "20:00")).contains("chồng lấn"))
    }

    @Test
    fun `a shift crossing midnight overlapping the early morning shift is rejected`() {
        assertTrue(message(shift("NIGHT", "22:00", "07:00"), shift("MORNING", "06:00", "14:00")).contains("chồng lấn"))
    }

    @Test
    fun `touching shifts are not overlapping`() {
        ShiftCatalog.parse(listOf(shift("A", "06:00", "14:00"), shift("B", "14:00", "06:00")))
    }

    @Test
    fun `binding from properties keeps order and values`() {
        val source = MapConfigurationPropertySource(
            mapOf(
                "app.report.max-range-days" to "90",
                "app.report.shifts[0].code" to "MORNING",
                "app.report.shifts[0].name" to "Ca sáng",
                "app.report.shifts[0].start" to "06:00",
                "app.report.shifts[0].end" to "14:00",
                "app.report.shifts[1].code" to "NIGHT",
                "app.report.shifts[1].name" to "Ca đêm",
                "app.report.shifts[1].start" to "23:00",
                "app.report.shifts[1].end" to "06:00",
            ),
        )
        val props = Binder(source).bind("app.report", ReportProperties::class.java).get()
        assertEquals(90, props.maxRangeDays)
        val catalog = ShiftCatalog(props)
        assertEquals(listOf("MORNING", "NIGHT"), catalog.shifts.map { it.code })
        assertEquals("Ca đêm", catalog.find("NIGHT")?.name)
    }

    @Test
    fun `max range days outside the allowed bounds is rejected`() {
        assertFailsWith<IllegalArgumentException> { ReportProperties(maxRangeDays = 0) }
        assertFailsWith<IllegalArgumentException> { ReportProperties(maxRangeDays = 3661) }
    }
}
