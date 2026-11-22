package com.qrorder.auth.security

/** Kết quả đối chiếu Guest JWT với phiên bàn trong database (BE-05, FR-AUTH-04, BR-S03). */
enum class GuestSessionCheck {
    /** Phiên OPEN hoặc BILLING và thiết bị đã gắn vào phiên. */
    ACTIVE,
    /** Phiên không tồn tại hoặc đã CLOSED / EXPIRED / FORCE_CLOSED. */
    SESSION_CLOSED,
    /** Phiên còn hoạt động nhưng thiết bị trong token không thuộc phiên (token giả/không khớp). */
    DEVICE_UNKNOWN,
}

/**
 * Cổng kiểm tra phiên của Guest JWT, do module `table` cài đặt (module `auth` không đọc repository của `table`).
 * Được gọi ở MỖI request REST và mỗi lệnh STOMP CONNECT có Guest JWT, nên phải là một truy vấn nhẹ theo khóa chính.
 */
fun interface GuestSessionGate {
    fun check(sessionId: Long, deviceId: String): GuestSessionCheck
}
