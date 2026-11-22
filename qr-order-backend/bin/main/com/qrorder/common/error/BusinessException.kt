package com.qrorder.common.error

import com.qrorder.common.api.ErrorDetail

/**
 * Lỗi nghiệp vụ do service ném ra; GlobalExceptionHandler chuyển thành ApiResponse lỗi với
 * HTTP status lấy từ [ErrorCode].
 *
 * [message] là thông điệp hiển thị cho người dùng (tiếng Việt), không chứa dữ liệu nhạy cảm.
 */
open class BusinessException(
    val errorCode: ErrorCode,
    message: String = errorCode.defaultMessage,
    val details: List<ErrorDetail> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ResourceNotFoundException(resource: String, id: Any?) :
    BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy $resource${id?.let { " #$it" } ?: ""}")

/**
 * Vượt giới hạn tần suất (BR-S09). [retryAfterSeconds] được GlobalExceptionHandler trả trong header `Retry-After`.
 */
class RateLimitExceededException(val retryAfterSeconds: Long) :
    BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED)
