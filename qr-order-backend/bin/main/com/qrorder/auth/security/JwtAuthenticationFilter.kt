package com.qrorder.auth.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filter JWT stateless: đọc `Authorization: Bearer <token>`, xác thực và đặt Authentication vào
 * SecurityContext cho request hiện tại. Không tạo HTTP session.
 *
 * Token lỗi không chặn request ngay (endpoint public vẫn dùng được); mã lỗi được lưu vào request
 * attribute để [RestAuthenticationEntryPoint] trả đúng 401 TOKEN_EXPIRED / TOKEN_INVALID /
 * ACCOUNT_DISABLED nếu endpoint cần đăng nhập.
 *
 * Không đăng ký làm @Component để tránh Spring Boot tự thêm vào servlet filter chain (chạy 2 lần);
 * SecurityConfig tạo và gắn vào security filter chain.
 */
class JwtAuthenticationFilter(
    private val principalResolver: AuthPrincipalResolver,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header != null && header.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            val token = header.substring(BEARER_PREFIX.length).trim()
            try {
                val principal = principalResolver.resolve(token)
                val context = SecurityContextHolder.createEmptyContext()
                context.authentication = principal.toAuthentication()
                SecurityContextHolder.setContext(context)
            } catch (ex: AuthTokenException) {
                SecurityContextHolder.clearContext()
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ex.errorCode)
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val BEARER_PREFIX = "Bearer "
        const val AUTH_ERROR_ATTRIBUTE = "com.qrorder.auth.error"
    }
}
