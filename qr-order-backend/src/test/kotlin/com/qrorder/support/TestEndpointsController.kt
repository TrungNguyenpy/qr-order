package com.qrorder.support

import com.qrorder.auth.security.CurrentPrincipal
import com.qrorder.common.api.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class TestBody(
    @field:NotBlank(message = "Tên không được trống")
    val name: String?,
    @field:Min(value = 1, message = "Số lượng tối thiểu là 1")
    val quantity: Int?,
)

/**
 * Endpoint CHỈ CÓ TRONG TEST (src/test) để kiểm chứng hạ tầng Giai đoạn 0:
 * phân quyền role/permission, xử lý lỗi validation, ánh xạ lỗi trigger PostgreSQL qua HTTP.
 */
@RestController
@RequestMapping("/api/v1")
class TestEndpointsController(private val jdbc: JdbcTemplate) {

    @GetMapping("/public/test/ping")
    fun ping() = ApiResponse.ok(mapOf("pong" to true))

    @GetMapping("/test/me")
    fun me() = ApiResponse.ok(mapOf("name" to CurrentPrincipal.get()?.name))

    @GetMapping("/test/staff")
    @PreAuthorize("hasRole('STAFF')")
    fun staffOnly() = ApiResponse.ok("staff")

    @GetMapping("/test/kitchen")
    @PreAuthorize("hasRole('KITCHEN')")
    fun kitchenOnly() = ApiResponse.ok("kitchen")

    @GetMapping("/test/admin")
    @PreAuthorize("hasRole('ADMIN')")
    fun adminOnly() = ApiResponse.ok("admin")

    @GetMapping("/test/payment")
    @PreAuthorize("@perm.has('PAYMENT_PROCESS')")
    fun paymentProcess() = ApiResponse.ok("payment")

    @PostMapping("/test/validation")
    fun validation(@Valid @RequestBody body: TestBody) = ApiResponse.ok(body)

    /** Khóa user bằng SQL để trigger LAST_ACTIVE_ADMIN (BR-A09) bắn lỗi P0001. */
    @PostMapping("/test/db/lock-user/{id}")
    fun lockUser(@PathVariable id: Long): ApiResponse<Int> =
        ApiResponse.ok(jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", id))
}
