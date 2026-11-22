package com.qrorder.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** Truy cập correlationId của request hiện tại (NFR-10). */
object CorrelationId {
    const val HEADER = "X-Correlation-Id"
    const val MDC_KEY = "correlationId"
    private val VALID = Regex("^[A-Za-z0-9-]{1,64}$")

    fun current(): String? = MDC.get(MDC_KEY)

    /** Chỉ nhận giá trị client gửi nếu đúng định dạng, tránh log injection. */
    fun sanitize(candidate: String?): String =
        candidate?.takeIf { VALID.matches(it) } ?: UUID.randomUUID().toString()
}

/**
 * Gắn correlationId cho mỗi request: đọc header X-Correlation-Id (nếu hợp lệ) hoặc sinh mới,
 * đặt vào MDC để mọi dòng log mang theo, và trả lại trong header response.
 * Chạy trước Spring Security để cả lỗi 401/403 cũng có correlationId.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val correlationId = CorrelationId.sanitize(request.getHeader(CorrelationId.HEADER))
        MDC.put(CorrelationId.MDC_KEY, correlationId)
        response.setHeader(CorrelationId.HEADER, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(CorrelationId.MDC_KEY)
        }
    }
}
