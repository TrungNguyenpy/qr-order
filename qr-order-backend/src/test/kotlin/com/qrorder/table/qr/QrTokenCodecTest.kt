package com.qrorder.table.qr

import com.qrorder.auth.token.TokenHasher
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.table.config.QrProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BR-S05, E7: qrToken dẫn xuất HMAC, DB chỉ lưu SHA-256; cấu hình QR. */
class QrTokenCodecTest {
    private val secret = "dGVzdC1vbmx5LXFyLXRva2VuLXNlY3JldC1xci1vcmRlci0yMDI2IQ=="
    private val hasher = TokenHasher()

    private fun codec(baseUrl: String = "https://order.example.vn/", secret: String = this.secret, prefix: String = "/t/") =
        QrTokenCodec(QrProperties(baseUrl = baseUrl, tokenSecret = secret, pathPrefix = prefix), hasher)

    @Test
    fun `token is deterministic per qr id, differs between ids, 256-bit URL-safe`() {
        val c = codec()
        val t1 = c.tokenFor(1)
        assertEquals(t1, c.tokenFor(1))
        assertNotEquals(t1, c.tokenFor(2))
        assertEquals(43, t1.length)
        assertTrue(QrTokenCodec.TOKEN_PATTERN.matches(t1))
        assertNotEquals(t1, codec(secret = "b3RoZXItc2VjcmV0LW90aGVyLXNlY3JldC1vdGhlci1zZWNyZXQ=").tokenFor(1), "Khóa khác -> token khác")
    }

    @Test
    fun `reproduce only when the stored hash matches, URL format`() {
        val c = codec()
        val token = c.tokenFor(7)
        assertEquals(token, c.reproduce(7, hasher.sha256Hex(token)))
        assertNull(c.reproduce(7, hasher.sha256Hex("dev-qr-T01")), "QR seed dev")
        assertNull(c.reproduce(8, hasher.sha256Hex(token)))
        assertEquals("https://order.example.vn/t/$token", c.urlFor(token))
        assertEquals("https://order.example.vn/qr/$token", codec(prefix = "/qr/").urlFor(token))
    }

    @Test
    fun `missing configuration - not configured, generation refused with QR_NOT_CONFIGURED`() {
        val c = QrTokenCodec(QrProperties(), hasher)
        assertFalse(c.configured)
        assertEquals(ErrorCode.QR_NOT_CONFIGURED, assertFailsWith<BusinessException> { c.requireConfigured() }.errorCode)
        assertEquals(ErrorCode.QR_NOT_CONFIGURED, assertFailsWith<BusinessException> { c.tokenFor(1) }.errorCode)
        assertFalse(codec(baseUrl = "").configured)
        assertFalse(codec(secret = " ").configured)
        assertTrue(codec().configured)
    }

    @Test
    fun `invalid configuration fails fast`() {
        assertFailsWith<IllegalStateException> { codec(secret = "c2hvcnQ=") } // 5 byte < 32
        assertFailsWith<IllegalStateException> { codec(secret = "khong phai base64!!") }
        assertFailsWith<IllegalStateException> { codec(baseUrl = "ftp://order.example.vn") }
        assertFailsWith<IllegalStateException> { codec(baseUrl = "https://order.example.vn?x=1") }
        assertFailsWith<IllegalStateException> { codec(prefix = "t") }
    }
}
