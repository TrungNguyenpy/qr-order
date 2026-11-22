package com.qrorder.report.csv

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** BE-38: CSV UTF-8 có BOM cho Excel, RFC 4180 và chống formula injection. */
class CsvWriterTest {

    private fun text(bytes: ByteArray) = bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)

    @Test
    fun `output starts with the UTF-8 byte order mark`() {
        val bytes = CsvWriter.write(listOf("a"), emptyList())
        assertContentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.copyOfRange(0, 3))
    }

    @Test
    fun `rows end with CRLF and Vietnamese text survives the round trip`() {
        val bytes = CsvWriter.write(listOf("Doanh thu gộp", "Ngày"), listOf(listOf(159000L, "Phở bò tái")))
        assertEquals("Doanh thu gộp,Ngày\r\n159000,Phở bò tái\r\n", text(bytes))
    }

    @Test
    fun `null becomes an empty cell and numbers are written as is`() {
        assertEquals("1,,-5,2.50\r\n", CsvWriter.line(listOf(1, null, -5L, java.math.BigDecimal("2.50"))))
    }

    @Test
    fun `cells with comma quote or line break are quoted and quotes doubled`() {
        assertEquals("\"a,b\"", CsvWriter.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvWriter.cell("say \"hi\""))
        assertEquals("\"x\ny\"", CsvWriter.cell("x\ny"))
    }

    @Test
    fun `text starting with a formula character gets a leading apostrophe`() {
        assertEquals("'=1+1", CsvWriter.cell("=1+1"))
        assertEquals("'+84901234567", CsvWriter.cell("+84901234567"))
        assertEquals("'-2", CsvWriter.cell("-2"))
        assertEquals("'@SUM(A1)", CsvWriter.cell("@SUM(A1)"))
        assertEquals("'\tx", CsvWriter.cell("\tx"))
    }

    @Test
    fun `a guarded cell that also needs quoting is both prefixed and quoted`() {
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"", CsvWriter.cell("=HYPERLINK(\"http://x\",\"y\")"))
    }

    @Test
    fun `negative numbers are not treated as formulas`() {
        assertEquals("-159000", CsvWriter.cell(-159000L))
    }

    @Test
    fun `empty string and normal text are unchanged`() {
        assertEquals("", CsvWriter.cell(""))
        assertEquals("Ca sáng", CsvWriter.cell("Ca sáng"))
    }
}
