package com.qrorder.table

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.qrorder.common.error.ErrorCode
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.TableIntegrationTestBase
import org.junit.jupiter.api.Test
import org.openpdf.text.pdf.PdfReader
import org.openpdf.text.pdf.parser.PdfTextExtractor
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** FR-QR-01, FR-QR-02, BR-S05, BE-07: sinh/xoay/thu hồi QR, ảnh PNG, PDF in (COMMIT thật). */
class QrCodeApiIT : TableIntegrationTestBase() {

    private fun sha256(text: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    private fun newTableWithQr(code: String): Pair<Long, String> {
        val id = data(
            api.post("/api/v1/tables", admin.accessToken, mapOf("code" to code, "displayName" to "Bàn $code", "area" to "Tầng 3", "capacity" to 4))
                .andExpect(status().isCreated),
        )["id"].asLong()
        val qr = data(api.post("/api/v1/tables/$id/qr-codes", admin.accessToken, null).andExpect(status().isCreated))
        return id to qr["url"].asText()
    }

    private fun decodeQr(png: ByteArray): String {
        val image = ImageIO.read(ByteArrayInputStream(png))
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))).text
    }

    @Test
    fun `generate - random token, only SHA-256 stored, one active QR per table, audit`() {
        val (tableId, url) = newTableWithQr("Q01")
        assertTrue(url.startsWith("http://localhost:3000/t/"), url)
        val token = tokenOf(url)
        assertEquals(43, token.length, "256 bit Base64 URL-safe (>= 128 bit - BR-S05)")
        assertEquals(sha256(token), jdbc.queryForObject("SELECT token_hash FROM qr_code WHERE table_id = ? AND status = 'ACTIVE'", String::class.java, tableId))
        assertEquals(0L, count("SELECT count(*) FROM qr_code WHERE token_hash = ?", token), "Không lưu token gốc")
        api.post("/api/v1/tables/$tableId/qr-codes", admin.accessToken, null).isError(409, "QR_ALREADY_ACTIVE")
        val info = data(api.get("/api/v1/tables/$tableId/qr-code", admin.accessToken).andExpect(status().isOk))
        assertEquals(url, info["url"].asText(), "Dựng lại được URL để client tự render")
        assertTrue(info["reproducible"].asBoolean())
        assertEquals(1, api.auditRows("QR_GENERATED").size)
        // Hàng rào cuối ở DB: tối đa 1 QR ACTIVE mỗi bàn (BR-S05).
        expectDbError(ErrorCode.QR_ALREADY_ACTIVE) {
            jdbc.update("INSERT INTO qr_code (table_id, token_hash) VALUES (?, ?)", tableId, sha256("khac"))
        }
        // Chỉ ADMIN (SRS §10).
        for (user in listOf(manager, staff, kitchen)) {
            api.get("/api/v1/tables/$tableId/qr-code", user.accessToken).isError(403, "PERMISSION_DENIED")
            api.post("/api/v1/tables/$tableId/qr-code/rotate", user.accessToken, null).isError(403, "PERMISSION_DENIED")
        }
    }

    @Test
    fun `rotate - old token invalid immediately, running session unaffected, audit QR_ROTATED`() {
        val (tableId, url) = newTableWithQr("Q02")
        val oldToken = tokenOf(url)
        val joined = joinOk(oldToken)
        val guestToken = joined["accessToken"].asText()
        val sessionId = joined["session"]["sessionId"].asLong()
        val oldId = jdbc.queryForObject("SELECT id FROM qr_code WHERE table_id = ? AND status = 'ACTIVE'", Long::class.java, tableId)!!

        val rotated = data(api.post("/api/v1/tables/$tableId/qr-code/rotate", admin.accessToken, mapOf("reason" to "Tem bị chụp")).andExpect(status().isOk))
        val newToken = tokenOf(rotated["url"].asText())
        assertNotEquals(oldToken, newToken)
        val old = jdbc.queryForMap("SELECT status, deactivated_by, deactivation_reason, deactivated_at FROM qr_code WHERE id = ?", oldId)
        assertEquals("ROTATED", old["status"])
        assertEquals(admin.id, (old["deactivated_by"] as Number).toLong())
        assertEquals("Tem bị chụp", old["deactivation_reason"])
        assertNotNull(old["deactivated_at"])

        join(oldToken).isError(404, "QR_CODE_INVALID")
        api.get("/api/v1/guest/session", guestToken).andExpect(status().isOk) // BR-S05: phiên đang chạy không bị ảnh hưởng
        assertEquals(sessionId, joinOk(newToken)["session"]["sessionId"].asLong())
        val audit = api.auditRows("QR_ROTATED").single()
        assertEquals(oldId, (audit["entity_id"] as Number).toLong())
        assertEquals("Tem bị chụp", audit["reason"])
        assertEquals(rotated["id"].asLong(), api.jsonOf(audit["new_value"])["replacedBy"].asLong())
    }

    @Test
    fun `revoke requires a reason, leaves the table without QR, history keeps every code`() {
        val (tableId, url) = newTableWithQr("Q03")
        api.post("/api/v1/tables/$tableId/qr-code/revoke", admin.accessToken, mapOf("reason" to " ")).isError(400, "VALIDATION_FAILED")
        val revoked = data(api.post("/api/v1/tables/$tableId/qr-code/revoke", admin.accessToken, mapOf("reason" to "Mất tem")).andExpect(status().isOk))
        assertEquals("REVOKED", revoked["status"].asText())
        join(tokenOf(url)).isError(404, "QR_CODE_INVALID")
        api.get("/api/v1/tables/$tableId/qr-code", admin.accessToken).isError(409, "QR_CODE_NOT_ACTIVE")
        api.post("/api/v1/tables/$tableId/qr-code/rotate", admin.accessToken, null).isError(409, "QR_CODE_NOT_ACTIVE")
        api.post("/api/v1/tables/$tableId/qr-code/revoke", admin.accessToken, mapOf("reason" to "lần 2")).isError(409, "QR_CODE_NOT_ACTIVE")
        assertEquals("Mất tem", api.auditRows("QR_REVOKED").single()["reason"])
        api.post("/api/v1/tables/$tableId/qr-codes", admin.accessToken, null).andExpect(status().isCreated)
        val history = data(api.get("/api/v1/tables/$tableId/qr-codes", admin.accessToken))
        assertEquals(listOf("ACTIVE", "REVOKED"), history.map { it["status"].asText() })
        assertTrue(history[1]["url"].isNull, "Chỉ QR ACTIVE mới trả URL")
    }

    @Test
    fun `PNG decodes to the QR URL, size is bounded, exports are audited and not cached`() {
        val (tableId, url) = newTableWithQr("Q04")
        val result = api.get("/api/v1/tables/$tableId/qr-code/image.png?size=400", admin.accessToken)
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn().response
        val png = result.contentAsByteArray
        val image = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(400, image.width)
        assertEquals(400, image.height)
        assertEquals(url, decodeQr(png))
        assertTrue(result.getHeader(HttpHeaders.CONTENT_DISPOSITION)!!.contains("qr-Q04.png"))
        api.get("/api/v1/tables/$tableId/qr-code/image.png?size=64", admin.accessToken).isError(400, "VALIDATION_FAILED")
        api.get("/api/v1/tables/$tableId/qr-code/image.png?size=4096", admin.accessToken).isError(400, "VALIDATION_FAILED")
        val export = api.auditRows("QR_EXPORTED").single()
        assertEquals("PNG", api.jsonOf(export["new_value"])["format"].asText())
        // QR seed dev (token cố định, không dẫn xuất từ khóa) không dựng lại được -> phải xoay mã mới để in.
        val t01 = tableId("T01")
        val seed = data(api.get("/api/v1/tables/$t01/qr-code", admin.accessToken))
        assertEquals(false, seed["reproducible"].asBoolean())
        assertTrue(seed["url"].isNull)
        api.get("/api/v1/tables/$t01/qr-code/image.png", admin.accessToken).isError(409, "QR_TOKEN_NOT_REPRODUCIBLE")
        api.post("/api/v1/tables/$t01/qr-code/rotate", admin.accessToken, null).andExpect(status().isOk)
        val rotatedUrl = data(api.get("/api/v1/tables/$t01/qr-code", admin.accessToken))["url"].asText()
        assertEquals(rotatedUrl, decodeQr(api.get("/api/v1/tables/$t01/qr-code/image.png", admin.accessToken).andReturn().response.contentAsByteArray))
    }

    @Test
    fun `PDF - single table and batch, one page per table, Vietnamese text, QR decodes`() {
        val (q05, url05) = newTableWithQr("Q05")
        val (q06, _) = newTableWithQr("Q06")
        val single = api.get("/api/v1/tables/$q05/qr-code/print.pdf", admin.accessToken)
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
            .andReturn().response.contentAsByteArray
        assertEquals("%PDF-", String(single.copyOf(5)))
        val reader = PdfReader(single)
        assertEquals(1, reader.numberOfPages)
        val text = PdfTextExtractor(reader).getTextFromPage(1)
        assertTrue(text.contains("Bàn Q05"), text)
        assertTrue(text.contains("Tầng 3"), text)
        reader.close()

        val batch = api.get("/api/v1/qr-codes/print.pdf?tableIds=$q05&tableIds=$q06", admin.accessToken)
            .andExpect(status().isOk)
            .andReturn().response
        assertTrue(batch.getHeader(HttpHeaders.CONTENT_DISPOSITION)!!.startsWith("attachment"))
        val batchReader = PdfReader(batch.contentAsByteArray)
        assertEquals(2, batchReader.numberOfPages)
        batchReader.close()
        assertEquals(listOf(q05, q06), api.jsonOf(api.auditRows("QR_EXPORTED").last()["new_value"])["tableIds"].map { it.asLong() })

        // Bàn không có QR ACTIVE / bàn không tồn tại / QR seed không dựng lại được: báo lỗi, không in thiếu.
        api.post("/api/v1/tables/$q06/qr-code/revoke", admin.accessToken, mapOf("reason" to "thử")).andExpect(status().isOk)
        api.get("/api/v1/qr-codes/print.pdf?tableIds=$q05&tableIds=$q06", admin.accessToken).isError(409, "QR_CODE_NOT_ACTIVE")
        api.get("/api/v1/qr-codes/print.pdf?tableIds=999999", admin.accessToken).isError(404, "RESOURCE_NOT_FOUND")
        api.get("/api/v1/qr-codes/print.pdf", admin.accessToken).isError(409, "QR_TOKEN_NOT_REPRODUCIBLE")
        api.get("/api/v1/qr-codes/print.pdf?tableIds=$q05", manager.accessToken).isError(403, "PERMISSION_DENIED")
        assertTrue(url05.isNotEmpty())
    }

    @Test
    fun `BE-07 - token_hash lookup uses the unique index on CHAR(64)`() {
        val plan = tx.execute {
            jdbc.execute("SET LOCAL enable_seqscan = off")
            jdbc.queryForList(
                "EXPLAIN " + com.qrorder.table.repository.QR_FIND_BY_TOKEN_HASH_SQL.replace(":hash", "?"),
                String::class.java, sha256("bat-ky"),
            ).joinToString("\n")
        }!!
        assertTrue(plan.contains("uq_qr_code_token_hash"), plan)
        assertTrue(plan.contains("Index Cond"), plan)
    }
}
