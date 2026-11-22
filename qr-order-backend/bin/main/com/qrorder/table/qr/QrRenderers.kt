package com.qrorder.table.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.qrorder.table.config.QrProperties
import org.openpdf.text.Document
import org.openpdf.text.Element
import org.openpdf.text.Font
import org.openpdf.text.Image
import org.openpdf.text.PageSize
import org.openpdf.text.Paragraph
import org.openpdf.text.pdf.BaseFont
import org.openpdf.text.pdf.PdfWriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer

/** Sinh ảnh PNG của mã QR bằng ZXing (FR-QR-01). Thuần, không phụ thuộc database. */
@Component
class QrImageRenderer {
    /**
     * Mã QR [content] cỡ [size] x [size] px, viền trắng [margin] module, mức sửa lỗi M (~15%, đủ cho tem dán bàn).
     * ZXing chọn version QR nhỏ nhất chứa được nội dung rồi phóng to vừa [size].
     */
    fun renderPng(content: String, size: Int, margin: Int): ByteArray {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        return ByteArrayOutputStream().use { out ->
            MatrixToImageWriter.writeToStream(matrix, PNG_FORMAT, out)
            out.toByteArray()
        }
    }

    companion object {
        const val PNG_FORMAT = "PNG"
    }
}

/** Một trang in: thông tin bàn + URL mã hóa trong QR. */
data class QrPrintPage(
    val tableCode: String,
    val displayName: String,
    val area: String?,
    val url: String,
)

/**
 * Xuất PDF in mã QR bằng OpenPDF (FR-QR-01): mỗi bàn một trang khổ A5 gồm tên bàn, mã bàn/khu vực, mã QR và hướng dẫn.
 * Font Unicode nhúng ([QrProperties.Pdf.fontPath]) để in đúng tiếng Việt; nếu không nạp được font thì dùng Helvetica
 * và bỏ dấu (ghi cảnh báo) để vẫn in được.
 */
@Component
class QrPdfRenderer(
    private val properties: QrProperties,
    private val imageRenderer: QrImageRenderer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** null = không nạp được font Unicode (dùng Helvetica, bỏ dấu). Nạp một lần, dùng lại cho mọi lần in. */
    private val unicodeFont: BaseFont? by lazy { loadUnicodeFont(properties.pdf.fontPath) }

    fun render(pages: List<QrPrintPage>): ByteArray {
        require(pages.isNotEmpty()) { "Không có trang nào để in" }
        val base = unicodeFont
        val titleFont = font(base, TITLE_SIZE)
        val textFont = font(base, TEXT_SIZE)
        val hintFont = font(base, HINT_SIZE)
        val qrSize = properties.pdf.qrSizePt

        val out = ByteArrayOutputStream()
        val document = Document(PageSize.A5, MARGIN, MARGIN, MARGIN, MARGIN)
        PdfWriter.getInstance(document, out)
        document.addTitle("QR Order - Mã QR bàn")
        document.addCreator("QR Order")
        document.open()
        pages.forEachIndexed { index, page ->
            if (index > 0) document.newPage()
            document.add(centered(text(base, page.displayName), titleFont, spacingAfter = 6f))
            val subtitle = listOfNotNull("Mã bàn: ${page.tableCode}", page.area?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
            document.add(centered(text(base, subtitle), textFont, spacingAfter = 12f))
            val png = imageRenderer.renderPng(page.url, PRINT_IMAGE_PX, PRINT_MARGIN_MODULES)
            val image = Image.getInstance(png)
            image.scaleAbsolute(qrSize, qrSize)
            image.setAlignment(Image.MIDDLE)
            document.add(image)
            document.add(centered(text(base, HINT), hintFont, spacingBefore = 12f))
        }
        document.close()
        return out.toByteArray()
    }

    private fun centered(content: String, font: Font, spacingBefore: Float = 0f, spacingAfter: Float = 0f): Paragraph =
        Paragraph(content, font).apply {
            setAlignment(Element.ALIGN_CENTER)
            setSpacingBefore(spacingBefore)
            setSpacingAfter(spacingAfter)
        }

    private fun font(base: BaseFont?, size: Float): Font =
        if (base != null) Font(base, size) else Font(Font.HELVETICA, size)

    /** Không có font Unicode: bỏ dấu tiếng Việt để Helvetica (WinAnsi) không in ra ký tự lỗi. */
    private fun text(base: BaseFont?, value: String): String = if (base != null) value else stripDiacritics(value)

    private fun loadUnicodeFont(path: String): BaseFont? = try {
        val bytes = readFontBytes(path.trim())
        // Tên phải kết thúc .ttf để OpenPDF đọc dạng TrueType; IDENTITY_H + nhúng font: hiển thị đúng mọi ký tự Unicode.
        BaseFont.createFont(FONT_NAME, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null)
    } catch (ex: Exception) {
        log.warn("Không nạp được font PDF '{}', dùng Helvetica và bỏ dấu tiếng Việt: {}", path, ex.message)
        null
    }

    private fun readFontBytes(path: String): ByteArray {
        val file = Path.of(path)
        if (path.isNotEmpty() && Files.isRegularFile(file)) return Files.readAllBytes(file)
        val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        return loader.getResourceAsStream(path.removePrefix("/"))?.use { it.readBytes() }
            ?: throw IllegalStateException("Không tìm thấy font: $path")
    }

    companion object {
        private const val MARGIN = 36f
        private const val TITLE_SIZE = 28f
        private const val TEXT_SIZE = 14f
        private const val HINT_SIZE = 13f
        /** Độ phân giải ảnh QR nhúng vào PDF (px) - đủ nét khi in ~10 cm. */
        private const val PRINT_IMAGE_PX = 1024
        private const val PRINT_MARGIN_MODULES = 1
        private const val FONT_NAME = "qr-order-unicode.ttf"
        const val HINT = "Quét mã QR để xem thực đơn và gọi món"

        fun stripDiacritics(value: String): String =
            Normalizer.normalize(value.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
    }
}
