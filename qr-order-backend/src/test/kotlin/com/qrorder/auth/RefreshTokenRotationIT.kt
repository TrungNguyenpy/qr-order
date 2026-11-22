package com.qrorder.auth

import com.qrorder.auth.domain.RefreshToken
import com.qrorder.auth.domain.RevokeReason
import com.qrorder.auth.domain.User
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.domain.UserStatus
import com.qrorder.auth.repository.RefreshTokenRepository
import com.qrorder.auth.security.JwtProperties
import com.qrorder.auth.token.RefreshTokenService
import com.qrorder.auth.token.RotationResult
import com.qrorder.auth.token.TokenHasher
import com.qrorder.common.error.ErrorCode
import com.qrorder.support.IntegrationTestBase
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Refresh token rotation theo DATABASE_DESIGN.md §5.3 (11), chạy trên PostgreSQL thật, COMMIT thật
 * (trigger `trg_refresh_token_rotation_complete` là DEFERRABLE INITIALLY DEFERRED).
 * Tương ứng RT-03..RT-05, RT-14..RT-18 của tools/schema-test/test_schema.py.
 */
class RefreshTokenRotationIT : IntegrationTestBase() {

    @Autowired
    lateinit var service: RefreshTokenService

    @Autowired
    lateinit var repository: RefreshTokenRepository

    @Autowired
    lateinit var hasher: TokenHasher

    @Autowired
    lateinit var jwtProperties: JwtProperties

    private var staffId = 0L

    @BeforeEach
    fun setUp() {
        staffId = fx.user("staff_rt", UserRole.STAFF)
    }

    private fun tokenByRaw(raw: String): RefreshToken = repository.findByTokenHash(hasher.sha256Hex(raw))!!

    @Test
    fun `rotation runs 3 steps in one transaction and links old token to new token`() {
        val login = service.issueForLogin(staffId)

        val result = service.rotate(login.rawToken)

        val rotated = assertIs<RotationResult.Rotated>(result)
        val old = tokenByRaw(login.rawToken)
        val next = tokenByRaw(rotated.token.rawToken)
        assertEquals(RevokeReason.ROTATED, old.revokeReason)
        assertNotNull(old.revokedAt)
        assertEquals(next.id, old.replacedById)
        assertEquals(login.familyId, next.familyId)
        assertEquals(null, next.revokedAt)
        assertEquals(1L, repository.countActiveInFamily(login.familyId))
    }

    @Test
    fun `reusing a rotated token revokes the whole family (REUSE_DETECTED)`() {
        val login = service.issueForLogin(staffId)
        val first = service.rotate(login.rawToken) as RotationResult.Rotated

        val reuse = service.rotate(login.rawToken)

        assertIs<RotationResult.ReuseDetected>(reuse)
        assertEquals(0L, repository.countActiveInFamily(login.familyId))
        assertEquals(RevokeReason.REUSE_DETECTED, tokenByRaw(first.token.rawToken).revokeReason)
    }

    @Test
    fun `two concurrent refreshes with the same token - only one rotates, the other is treated as reuse`() {
        val login = service.issueForLogin(staffId)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit(Callable { start.await(); service.rotate(login.rawToken) })
            }
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it is RotationResult.Rotated }, "Kết quả: $results")
            assertEquals(1, results.count { it is RotationResult.ReuseDetected }, "Kết quả: $results")
            assertEquals(0L, repository.countActiveInFamily(login.familyId))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `incomplete rotation (step 3 missing) fails at COMMIT with REFRESH_TOKEN_ROTATION_INCOMPLETE and rolls back`() {
        val login = service.issueForLogin(staffId)
        val oldId = tokenByRaw(login.rawToken).id!!

        val error = expectDbError(ErrorCode.REFRESH_TOKEN_ROTATION_INCOMPLETE) {
            tx.executeWithoutResult {
                assertEquals(1, repository.markRotated(oldId)) // bước 1
                repository.saveAndFlush(                        // bước 2
                    RefreshToken(staffId, login.familyId, hasher.sha256Hex(hasher.newRawToken()), OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)),
                )
                // Thiếu bước 3 (linkReplacement): lỗi chỉ xuất hiện lúc COMMIT.
            }
        }

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.errorCode.httpStatus)
        assertEquals("P0001", error.sqlState)
        val old = tokenByRaw(login.rawToken)
        assertEquals(null, old.revokedAt, "Transaction phải rollback toàn bộ")
        assertEquals(1L, repository.countActiveInFamily(login.familyId))
    }

    @Test
    fun `SET CONSTRAINTS ALL IMMEDIATE surfaces the deferred check inside the transaction`() {
        val login = service.issueForLogin(staffId)
        val oldId = tokenByRaw(login.rawToken).id!!

        expectDbError(ErrorCode.REFRESH_TOKEN_ROTATION_INCOMPLETE) {
            tx.executeWithoutResult {
                jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE")
                repository.markRotated(oldId)
            }
        }
    }

    @Test
    fun `locking a user revokes all active refresh tokens immediately via trigger (BR-A04)`() {
        service.issueForLogin(staffId)
        service.issueForLogin(staffId)

        tx.executeWithoutResult {
            val user = em.find(User::class.java, staffId)
            user.status = UserStatus.LOCKED
        }

        assertEquals(0L, fx.count("SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", staffId))
        assertEquals(2L, fx.count("SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoke_reason = 'USER_LOCKED'", staffId))
    }

    @Test
    fun `changing role revokes tokens with ROLE_CHANGED`() {
        service.issueForLogin(staffId)

        tx.executeWithoutResult { em.find(User::class.java, staffId).role = UserRole.KITCHEN }

        assertEquals(1L, fx.count("SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoke_reason = 'ROLE_CHANGED'", staffId))
    }

    @Test
    fun `permission change revokes tokens through the service (PERMISSION_CHANGED)`() {
        service.issueForLogin(staffId)

        assertEquals(1, service.revokeAllForUser(staffId, RevokeReason.PERMISSION_CHANGED))
        assertEquals(0L, fx.count("SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", staffId))
    }

    @Test
    fun `logout revokes the family and unknown or expired tokens are rejected without side effects`() {
        val login = service.issueForLogin(staffId)
        assertEquals(RotationResult.Invalid, service.rotate("khong-ton-tai-" + UUID.randomUUID()))

        // Đồng hồ tương lai (sau TTL) -> token còn hiệu lực nhưng đã hết hạn.
        val futureClock = Clock.offset(Clock.systemUTC(), jwtProperties.refreshTokenTtl.plus(Duration.ofMinutes(1)))
        val futureService = RefreshTokenService(repository, hasher, jwtProperties, futureClock)
        assertEquals(RotationResult.Expired, futureService.rotate(login.rawToken))
        assertEquals(1L, repository.countActiveInFamily(login.familyId))

        assertEquals(1, service.logout(login.rawToken))
        assertTrue(tokenByRaw(login.rawToken).isRevoked)
        assertEquals(RevokeReason.LOGOUT, tokenByRaw(login.rawToken).revokeReason)
    }
}
