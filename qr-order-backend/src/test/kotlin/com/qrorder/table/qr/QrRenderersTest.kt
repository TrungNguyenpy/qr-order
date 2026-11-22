package com.qrorder.table.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.qrorder.table.config.QrProperties
import org.junit.jupiter.api.Test
import org.openpdf.text.pdf.PdfReader
import org.openpdf.text.pdf.parser.PdfTextExtractor
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FR-QR-01: ảnh PNG (ZXing) giải mã được, PDF (OpenPDF) đúng số trang và in được tiếng Việt. */
class QrRenderersTest {
    private val url = "https://order.example.vn/t/Zm9vYmFyLXRva2VuLXZhbHVlLWZvci10ZXN0aW5nLTEyMw"
    private val images = QrImageRenderer()

    private fun decode(png: ByteArray): String {
        val image = ImageIO.read(ByteArrayInputStream(png))
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))).text
    }

    @Test
    fun `PNG has the requested size and decodes to the URL`() {
        for (size in listOf(128, 512, 1024)) {
            val png = images.renderPng(url, size, 2)
            val image = ImageIO.read(ByteArrayInputStream(png))
            assertEquals(size, image.width)
            assertEquals(size, image.height)
            assertEquals(url, decode(png))
        }
    }

    @Test
    fun `PDF - one page per table with Vietnamese text from the bundled Unicode font`() {
        val renderer = QrPdfRenderer(QrProperties(), images)
        val pdf = renderer.render(
            listOf(
                QrPrintPage("T01", "Bàn 1", "Tầng 1", url),
                QrPrintPage("SV-02", "Bàn sân vườn", null, "$url-2"),
            ),
        )
        val reader = PdfReader(pdf)
        assertEquals(2, reader.numberOfPages)
        val extractor = PdfTextExtractor(reader)
        val page1 = extractor.getTextFromPage(1)
        assertTrue(page1.contains("Bàn 1") && page1.contains("Tầng 1") && page1.contains("Mã bàn: T01"), page1)
        assertTrue(page1.contains(QrPdfRenderer.HINT), page1)
        assertTrue(extractor.getTextFromPage(2).contains("Bàn sân vườn"))
        reader.close()
    }

    @Test
    fun `missing font falls back to Helvetica without diacritics instead of failing`() {
        val renderer = QrPdfRenderer(QrProperties(pdf = QrProperties.Pdf(fontPath = "/khong/ton/tai.ttf")), images)
        val reader = PdfReader(renderer.render(listOf(QrPrintPage("T01", "Bàn đôi", "Tầng 1", url))))
        val text = PdfTextExtractor(reader).getTextFromPage(1)
        assertTrue(text.contains("Ban doi") && text.contains("Tang 1"), text)
        reader.close()
        assertEquals("Ban doi Tang 1 Duong", QrPdfRenderer.stripDiacritics("Bàn đôi Tầng 1 Đường"))
    }
}
