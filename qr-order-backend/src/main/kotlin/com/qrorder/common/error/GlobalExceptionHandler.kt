package com.qrorder.common.error

import com.qrorder.common.api.ApiResponse
import com.qrorder.common.api.ErrorDetail
import jakarta.persistence.OptimisticLockException
import jakarta.persistence.PersistenceException
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.TransactionSystemException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Chuyển mọi exception thành [ApiResponse] lỗi với HTTP status và mã lỗi thống nhất.
 *
 * Nguyên tắc bảo mật (NFR-04, quy tắc 7): không trả stacktrace, câu SQL hay thông điệp gốc của
 * database ra client; chi tiết chỉ ghi log phía server kèm correlationId.
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val dbErrorTranslator: DatabaseErrorTranslator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(ex: BusinessException): ResponseEntity<ApiResponse<Nothing>> {
        if (ex.errorCode.httpStatus.is5xxServerError()) log.error("Lỗi nghiệp vụ {}", ex.errorCode, ex)
        else log.debug("Lỗi nghiệp vụ {}: {}", ex.errorCode, ex.message)
        val response = respond(ex.errorCode, ex.message ?: ex.errorCode.defaultMessage, ex.details)
        if (ex is RateLimitExceededException) {
            return ResponseEntity.status(response.statusCode)
                .header(HttpHeaders.RETRY_AFTER, ex.retryAfterSeconds.coerceAtLeast(1).toString())
                .body(response.body)
        }
        return response
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val details = ex.bindingResult.fieldErrors.map { ErrorDetail(it.field, it.defaultMessage ?: "Không hợp lệ") } +
            ex.bindingResult.globalErrors.map { ErrorDetail(null, it.defaultMessage ?: "Không hợp lệ") }
        return respond(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage, details)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ApiResponse<Nothing>> {
        val details = ex.constraintViolations.map { ErrorDetail(it.propertyPath.toString(), it.message) }
        return respond(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage, details)
    }

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
    )
    fun handleMalformed(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.debug("Yêu cầu sai định dạng: {}", ex.message)
        return respond(ErrorCode.MALFORMED_REQUEST)
    }

    /** Upload vượt spring.servlet.multipart.max-file-size (Giai đoạn 2: ảnh món tối đa 5MB). */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUpload(ex: MaxUploadSizeExceededException) = respond(ErrorCode.IMAGE_TOO_LARGE)

    /** Thiếu phần `file`, body multipart hỏng, hoặc Content-Type không được endpoint hỗ trợ. */
    @ExceptionHandler(
        MissingServletRequestPartException::class,
        MultipartException::class,
        HttpMediaTypeNotSupportedException::class,
    )
    fun handleBadMultipart(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.debug("Yêu cầu multipart/Content-Type không hợp lệ: {}", ex.message)
        return respond(ErrorCode.MALFORMED_REQUEST)
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotAllowed(ex: HttpRequestMethodNotSupportedException) = respond(ErrorCode.METHOD_NOT_ALLOWED)

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(ex: NoResourceFoundException) = respond(ErrorCode.RESOURCE_NOT_FOUND)

    /**
     * AccessDeniedException từ method security (@PreAuthorize) được ném bên trong controller nên
     * đi qua advice này trước ExceptionTranslationFilter. Người chưa đăng nhập nhận 401, còn lại 403.
     */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ApiResponse<Nothing>> {
        val auth = SecurityContextHolder.getContext().authentication
        return if (auth == null || auth is AnonymousAuthenticationToken || !auth.isAuthenticated) {
            respond(ErrorCode.UNAUTHENTICATED)
        } else {
            respond(ErrorCode.PERMISSION_DENIED)
        }
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(ex: AuthenticationException) = respond(ErrorCode.UNAUTHENTICATED)

    @ExceptionHandler(ObjectOptimisticLockingFailureException::class, OptimisticLockException::class)
    fun handleOptimisticLock(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.info("Xung đột optimistic lock: {}", ex.message)
        return respond(ErrorCode.CONCURRENT_MODIFICATION)
    }

    /**
     * Lỗi database: gồm lỗi lúc thực thi câu lệnh và lỗi lúc COMMIT (trigger DEFERRABLE
     * như BILL_PAYMENT_STATUS_MISMATCH, REFRESH_TOKEN_ROTATION_INCOMPLETE).
     */
    @ExceptionHandler(DataAccessException::class, TransactionSystemException::class, PersistenceException::class)
    fun handleDatabase(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        val translated = dbErrorTranslator.translate(ex)
        if (translated == null) {
            log.error("Lỗi truy cập dữ liệu chưa phân loại", ex)
            return respond(ErrorCode.INTERNAL_ERROR)
        }
        val code = translated.errorCode
        if (code.httpStatus.is5xxServerError()) {
            log.error(
                "Vi phạm toàn vẹn dữ liệu sqlState={} dbCode={} constraint={} message={}",
                translated.sqlState, translated.dbCode, translated.constraint, translated.serverMessage, ex,
            )
        } else {
            log.info(
                "Lỗi database đã ánh xạ {} (sqlState={} dbCode={} constraint={})",
                code, translated.sqlState, translated.dbCode, translated.constraint,
            )
        }
        return respond(code)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.error("Lỗi không mong đợi", ex)
        return respond(ErrorCode.INTERNAL_ERROR)
    }

    private fun respond(
        code: ErrorCode,
        message: String = code.defaultMessage,
        details: List<ErrorDetail> = emptyList(),
    ): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(code.httpStatus).body(ApiResponse.error(code.name, message, details))
}
