package com.qrorder.auth.security

import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.token.TokenHasher
import com.qrorder.common.error.ErrorCode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class JwtServiceTest {

    private val secret = Base64.getEncoder().encodeToString(ByteArray(48) { it.toByte() })
    private val props = JwtProperties(issuer = "qr-order-test", secret = secret)
    private val t0 = Instant.parse("2026-11-20T03:00:00Z")
    private fun service(at: Instant = t0, p: JwtProperties = props) = JwtService(p, Clock.fixed(at, ZoneOffset.UTC))

    @Test
    fun `staff token round trip`() {
        val token = service().issueStaffAccessToken(5, "cashier", UserRole.STAFF).token
        assertEquals(StaffPrincipal(5, "cashier", UserRole.STAFF), service().parse(token))
    }

    @Test
    fun `guest token round trip`() {
        val token = service().issueGuestToken(42, "device-0001", Duration.ofMinutes(15)).token
        assertEquals(GuestPrincipal(42, "device-0001"), service().parse(token))
    }

    @Test
    fun `expired, tampered, foreign issuer and other key tokens are rejected`() {
        val token = service().issueStaffAccessToken(5, "cashier", UserRole.STAFF).token

        fun codeOf(block: () -> Unit) = assertFailsWith<AuthTokenException> { block() }.errorCode

        assertEquals(ErrorCode.TOKEN_EXPIRED, codeOf { service(t0.plus(Duration.ofMinutes(20))).parse(token) })
        assertEquals(ErrorCode.TOKEN_INVALID, codeOf { service().parse(token.dropLast(2) + "xx") })
        assertEquals(ErrorCode.TOKEN_INVALID, codeOf { service(p = props.copy(issuer = "khac")).parse(token) })
        val otherKey = props.copy(secret = Base64.getEncoder().encodeToString(ByteArray(48) { 7 }))
        assertEquals(ErrorCode.TOKEN_INVALID, codeOf { service(p = otherKey).parse(token) })
    }

    @Test
    fun `secret shorter than 256 bits is refused at startup`() {
        val weak = props.copy(secret = Base64.getEncoder().encodeToString(ByteArray(16)))
        assertFailsWith<IllegalArgumentException> { service(p = weak) }
    }

    @Test
    fun `token hasher produces 64 lowercase hex and 256-bit random tokens`() {
        val hasher = TokenHasher()
        val hash = hasher.sha256Hex("dev-qr-T01")
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hash))
        // Giá trị khớp encode(sha256('dev-qr-T01')) của seed V2.
        assertEquals("bbfd8b7e6f86f3a7de7011379aa9bc1bae3b7593a55a2ff84c03c2aba0e06cef", hash)
        assertNotEquals(hasher.newRawToken(), hasher.newRawToken())
        assertEquals(43, hasher.newRawToken().length)
    }
}
