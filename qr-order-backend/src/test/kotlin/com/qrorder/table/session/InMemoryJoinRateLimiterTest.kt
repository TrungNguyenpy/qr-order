package com.qrorder.table.session

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BR-S09: sliding window trong bộ nhớ (thuần, không Spring). */
class InMemoryJoinRateLimiterTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val window = Duration.ofMinutes(1)

    @Test
    fun `allows up to the limit then rejects with retry-after, window slides`() {
        val clock = MutableClock(Instant.parse("2026-11-20T10:00:00Z"))
        val limiter = InMemoryJoinRateLimiter(clock)
        repeat(10) { i ->
            assertTrue(limiter.tryAcquire("join:1.2.3.4", 10, window).allowed, "lần ${i + 1}")
            clock.now = clock.now.plusSeconds(1)
        }
        val denied = limiter.tryAcquire("join:1.2.3.4", 10, window)
        assertFalse(denied.allowed)
        assertEquals(50, denied.retryAfterSeconds, "Yêu cầu đầu (10:00:00) hết hạn lúc 10:01:00, hiện 10:00:10")
        clock.now = Instant.parse("2026-11-20T10:01:00Z")
        assertTrue(limiter.tryAcquire("join:1.2.3.4", 10, window).allowed, "Yêu cầu đầu đã ra khỏi cửa sổ")
        assertFalse(limiter.tryAcquire("join:1.2.3.4", 10, window).allowed)
    }

    @Test
    fun `rejected attempts do not extend the wait, keys are isolated, reset clears everything`() {
        val clock = MutableClock(Instant.parse("2026-11-20T10:00:00Z"))
        val limiter = InMemoryJoinRateLimiter(clock)
        repeat(2) { assertTrue(limiter.tryAcquire("a", 2, window).allowed) }
        repeat(5) { assertFalse(limiter.tryAcquire("a", 2, window).allowed) }
        assertTrue(limiter.tryAcquire("b", 2, window).allowed)
        clock.now = clock.now.plusSeconds(60)
        assertTrue(limiter.tryAcquire("a", 2, window).allowed, "Lần bị từ chối không được ghi vào cửa sổ")
        limiter.reset()
        assertEquals(0, limiter.trackedKeys())
        repeat(2) { assertTrue(limiter.tryAcquire("a", 2, window).allowed) }
    }

    @Test
    fun `concurrent callers never exceed the limit`() {
        val limiter = InMemoryJoinRateLimiter(Clock.systemUTC())
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (1..200).map { pool.submit<Boolean> { start.await(); limiter.tryAcquire("same", 10, window).allowed } }
        start.countDown()
        val allowed = results.count { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(10, allowed)
    }
}
