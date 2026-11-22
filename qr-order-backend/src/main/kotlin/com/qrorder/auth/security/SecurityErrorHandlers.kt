package com.qrorder.auth.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.qrorder.common.api.ApiResponse
import com.qrorder.common.error.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

/** Ghi lỗi bảo mật theo đúng định dạng ApiResponse (lỗi xảy ra trong filter, ngoài @RestControllerAdvice). */
@Component
class SecurityErrorWriter(private val objectMapper: ObjectMapper) {
    fun write(response: HttpServletResponse, code: ErrorCode) {
        response.status = code.httpStatus.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, ApiResponse.error(code.name, code.defaultMessage))
    }
}

/** 401: chưa đăng nhập hoặc token lỗi (mã cụ thể do JwtAuthenticationFilter ghi vào request attribute). */
class RestAuthenticationEntryPoint(private val writer: SecurityErrorWriter) : AuthenticationEntryPoint {
    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        val code = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE) as? ErrorCode ?: ErrorCode.UNAUTHENTICATED
        writer.write(response, code)
    }
}

/** 403: đã đăng nhập nhưng không đủ quyền theo luật URL. */
class RestAccessDeniedHandler(private val writer: SecurityErrorWriter) : AccessDeniedHandler {
    override fun handle(request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException) {
        writer.write(response, ErrorCode.ACCESS_DENIED)
    }
}
