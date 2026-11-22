package com.qrorder.auth.token

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * Sinh token ngẫu nhiên và băm SHA-256 hex (64 ký tự thường) - định dạng lưu ở cột token_hash của
 * refresh_token và qr_code (CHECK ~ '^[0-9a-f]{64}$'). Không bao giờ lưu token gốc (NFR-04, BR-S05).
 */
@Component
class TokenHasher {
    private val random = SecureRandom()

    /** 256 bit ngẫu nhiên, Base64 URL-safe không padding. */
    fun newRawToken(): String {
        val bytes = ByteArray(RAW_TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    companion object {
        const val RAW_TOKEN_BYTES = 32
    }
}
