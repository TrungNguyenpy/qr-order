package com.qrorder.table.session

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Kết quả kiểm tra rate limit. [retryAfterSeconds] chỉ có nghĩa khi bị từ chối. */
data class RateLimitDecision(val allowed: Boolean, val retryAfterSeconds: Long = 0)

/**
 * Giới hạn tần suất join phiên (BR-S09: 10 yêu cầu/phút/IP - giá trị lấy từ restaurant_settings.join_rate_limit_per_minute)
 * và nhập PIN. Tách interface để test mock/reset; bản cài đặt mặc định lưu trong bộ nhớ (phù hợp một instance, DEC-15).
 */
interface JoinRateLimiter {
    /** Ghi nhận một yêu cầu của [key]; từ chối nếu trong [window] đã có >= [limit] yêu cầu được chấp nhận. */
    fun tryAcquire(key: String, limit: Int, window: Duration): RateLimitDecision

    /** Xóa toàn bộ bộ đếm (dùng trong test). */
    fun reset()
}

/**
 * Sliding window log trong bộ nhớ: mỗi key giữ thời điểm các yêu cầu được chấp nhận trong cửa sổ gần nhất.
 * Yêu cầu bị từ chối không được ghi (không kéo dài thời gian chờ). Key không còn yêu cầu trong cửa sổ được dọn định
 * kỳ theo số lần gọi để bộ nhớ không tăng mãi. Mất bộ đếm khi khởi động lại là chấp nhận được (chỉ chống spam).
 */
@Component
class InMemoryJoinRateLimiter(private val clock: Clock) : JoinRateLimiter {
    private val buckets = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val calls = AtomicLong()

    override fun tryAcquire(key: String, limit: Int, window: Duration): RateLimitDecision {
        require(limit > 0) { "limit phải > 0" }
        val now = clock.millis()
        val windowMs = window.toMillis()
        if (calls.incrementAndGet() % CLEANUP_EVERY == 0L) cleanup(now, windowMs)
        val bucket = buckets.computeIfAbsent(key) { ArrayDeque() }
        synchronized(bucket) {
            while (bucket.isNotEmpty() && bucket.peekFirst() <= now - windowMs) bucket.pollFirst()
            if (bucket.size >= limit) {
                val retryMs = bucket.peekFirst() + windowMs - now
                return RateLimitDecision(allowed = false, retryAfterSeconds = ((retryMs + 999) / 1000).coerceAtLeast(1))
            }
            bucket.addLast(now)
            return RateLimitDecision(allowed = true)
        }
    }

    override fun reset() {
        buckets.clear()
    }

    /** Số key đang theo dõi (cho test/giám sát). */
    fun trackedKeys(): Int = buckets.size

    private fun cleanup(now: Long, windowMs: Long) {
        buckets.entries.removeIf { (_, bucket) ->
            synchronized(bucket) { bucket.isEmpty() || bucket.peekLast() <= now - windowMs }
        }
    }

    companion object {
        private const val CLEANUP_EVERY = 1_000L
    }
}
