package com.qrorder.common.web

import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.net.InetAddress

/**
 * Thông tin request HTTP hiện tại dùng cho audit (NFR-10, BR-A05).
 *
 * Địa chỉ IP lấy từ `HttpServletRequest.remoteAddr`. Khi chạy sau reverse proxy, cấu hình
 * `server.forward-headers-strategy: framework` (application.yml) để Spring thay remoteAddr bằng IP
 * trong X-Forwarded-For của proxy tin cậy.
 */
object ClientRequestInfo {
    /** Chỉ nhận chuỗi IP dạng số (IPv4 đủ 4 phần, hoặc IPv6 có dấu ':') để InetAddress.getByName KHÔNG tra DNS. */
    private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val IPV6_LITERAL = Regex("^[0-9A-Fa-f:.]{2,45}$")

    /** IP của client trong request hiện tại; null nếu không có request (scheduler, khởi động) hoặc không hợp lệ. */
    fun currentIp(): InetAddress? {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes ?: return null
        return parseIp(attributes.request.remoteAddr)
    }

    fun parseIp(raw: String?): InetAddress? {
        val candidate = raw?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return null
        val ipv4 = IPV4_LITERAL.matches(candidate) && candidate.split('.').all { it.toInt() <= 255 }
        val literal = ipv4 || (candidate.contains(':') && IPV6_LITERAL.matches(candidate))
        if (!literal) return null
        return try {
            InetAddress.getByName(candidate)
        } catch (ex: Exception) {
            null
        }
    }
}
