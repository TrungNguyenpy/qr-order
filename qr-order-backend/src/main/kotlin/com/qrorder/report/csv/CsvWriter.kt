package com.qrorder.report.csv

/**
 * Ghi CSV chuẩn RFC 4180 cho Excel (BE-38): UTF-8 có BOM để Excel nhận đúng tiếng Việt, xuống dòng CRLF, ô chứa dấu phẩy,
 * nháy kép hoặc xuống dòng được đặt trong nháy kép (nháy kép bên trong nhân đôi).
 *
 * Chống CSV/formula injection: ô văn bản bắt đầu bằng `=`, `+`, `-`, `@`, tab hoặc CR được thêm dấu nháy đơn ở đầu để Excel
 * không hiểu là công thức. Ô số ([Number]) ghi nguyên giá trị, không bị thêm nháy (số âm vẫn là số).
 */
object CsvWriter {
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val EOL = "\r\n"
    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@', '\t', '\r')

    fun write(header: List<String>, rows: List<List<Any?>>): ByteArray {
        val text = buildString {
            append(line(header))
            rows.forEach { append(line(it)) }
        }
        return BOM + text.toByteArray(Charsets.UTF_8)
    }

    internal fun line(cells: List<Any?>): String = cells.joinToString(separator = ",", postfix = EOL) { cell(it) }

    internal fun cell(value: Any?): String {
        if (value == null) return ""
        if (value is Number) return value.toString()
        var text = value.toString()
        if (text.isNotEmpty() && text[0] in FORMULA_PREFIXES) text = "'$text"
        val needsQuotes = text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }
}
