package com.qrorder.report.domain

import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** BE-34: khoảng from/to của API báo cáo (múi giờ nghiệp vụ Asia/Ho_Chi_Minh, to là cận trên loại trừ). */
class ReportRangeTest {

    private val today = LocalDate.of(2026, 11, 22)

    private fun resolve(from: String?, to: String?, maxDays: Int = 366) = ReportRange.resolve(from, to, today, maxDays)

    private fun assertInvalid(from: String?, to: String?, maxDays: Int = 366) {
        val ex = assertFailsWith<BusinessException> { resolve(from, to, maxDays) }
        assertEquals(ErrorCode.INVALID_REPORT_RANGE, ex.errorCode)
    }

    @Test
    fun `no parameters means today in the business time zone`() {
        val r = resolve(null, null)
        assertEquals("2026-11-22T00:00+07:00", r.from.toString())
        assertEquals("2026-11-23T00:00+07:00", r.to.toString())
        assertEquals(today, r.fromDate)
        assertEquals(today, r.toDate)
    }

    @Test
    fun `blank parameters are treated as missing`() {
        assertEquals(today, resolve("", "  ").fromDate)
    }

    @Test
    fun `date to is inclusive - the whole last day is counted`() {
        val r = resolve("2026-11-20", "2026-11-21")
        assertEquals("2026-11-20T00:00+07:00", r.from.toString())
        assertEquals("2026-11-22T00:00+07:00", r.to.toString())
        assertEquals(LocalDate.of(2026, 11, 20), r.fromDate)
        assertEquals(LocalDate.of(2026, 11, 21), r.toDate)
    }

    @Test
    fun `a single day range is allowed`() {
        val r = resolve("2026-11-21", "2026-11-21")
        assertEquals("2026-11-22T00:00+07:00", r.to.toString())
    }

    @Test
    fun `date time with offset is used as given with exclusive upper bound`() {
        val r = resolve("2026-11-21T06:00:00+07:00", "2026-11-21T14:00:00+07:00")
        assertEquals("2026-11-21T06:00+07:00", r.from.toString())
        assertEquals("2026-11-21T14:00+07:00", r.to.toString())
        assertEquals(LocalDate.of(2026, 11, 21), r.toDate)
    }

    @Test
    fun `date time in UTC keeps the instant and reports business dates`() {
        val r = resolve("2026-11-20T17:00:00Z", "2026-11-21T17:00:00Z")
        assertEquals(LocalDate.of(2026, 11, 21), r.fromDate)
        assertEquals(LocalDate.of(2026, 11, 21), r.toDate)
    }

    @Test
    fun `a plus sign turned into a space by the query string is repaired`() {
        val r = resolve("2026-11-21T06:00:00 07:00", "2026-11-21T14:00:00 07:00")
        assertEquals("2026-11-21T06:00+07:00", r.from.toString())
    }

    @Test
    fun `date and date time can be mixed`() {
        val r = resolve("2026-11-21", "2026-11-22T12:00:00+07:00")
        assertEquals("2026-11-21T00:00+07:00", r.from.toString())
        assertEquals("2026-11-22T12:00+07:00", r.to.toString())
    }

    @Test
    fun `only one bound is rejected`() {
        assertInvalid("2026-11-20", null)
        assertInvalid(null, "2026-11-20")
    }

    @Test
    fun `malformed values are rejected`() {
        assertInvalid("21-11-2026", "2026-11-22")
        assertInvalid("2026-11-21", "not-a-date")
        assertInvalid("2026-02-30", "2026-03-01")
        assertInvalid("2026-11-21T06:00:00", "2026-11-21T07:00:00+07:00")
    }

    @Test
    fun `from after to is rejected`() {
        assertInvalid("2026-11-22", "2026-11-21")
    }

    @Test
    fun `empty date time range is rejected`() {
        assertInvalid("2026-11-21T06:00:00+07:00", "2026-11-21T06:00:00+07:00")
    }

    @Test
    fun `range longer than the configured maximum is rejected and exactly the maximum is allowed`() {
        assertInvalid("2026-01-01", "2026-04-01", maxDays = 90)
        val r = resolve("2026-01-01", "2026-03-31", maxDays = 90)
        assertEquals(LocalDate.of(2026, 3, 31), r.toDate)
    }
}
