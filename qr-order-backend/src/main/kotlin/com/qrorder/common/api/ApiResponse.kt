package com.qrorder.common.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.qrorder.common.web.CorrelationId
import java.time.Instant

/**
 * Định dạng phản hồi thống nhất cho mọi API REST (hợp đồng dữ liệu dùng chung cho Customer Web,
 * Admin Dashboard và Staff App).
 *
 * Thành công: `{ "success": true, "data": {...}, "correlationId": "...", "timestamp": "..." }`
 * Lỗi:        `{ "success": false, "error": { "code": "ORDER_NOT_OPEN", "message": "...", "details": [...] }, ... }`
 *
 * `error.code` là mã máy đọc được (ErrorCode); client dựa vào mã này để hiển thị trạng thái Error.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null,
    val correlationId: String? = CorrelationId.current(),
    val timestamp: Instant = Instant.now(),
) {
    companion object {
        fun <T> ok(data: T): ApiResponse<T> = ApiResponse(success = true, data = data)

        fun okEmpty(): ApiResponse<Unit> = ApiResponse(success = true)

        fun error(code: String, message: String, details: List<ErrorDetail>? = null): ApiResponse<Nothing> =
            ApiResponse(success = false, error = ApiError(code, message, details?.takeIf { it.isNotEmpty() }))
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(
    val code: String,
    val message: String,
    val details: List<ErrorDetail>? = null,
)

/** Chi tiết lỗi, thường là lỗi validation theo từng trường. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorDetail(
    val field: String? = null,
    val message: String,
)
