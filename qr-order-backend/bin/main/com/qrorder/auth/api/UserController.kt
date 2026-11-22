package com.qrorder.auth.api

import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.domain.UserStatus
import com.qrorder.auth.dto.ChangeUserRoleRequest
import com.qrorder.auth.dto.ChangeUserStatusRequest
import com.qrorder.auth.dto.CreateUserRequest
import com.qrorder.auth.dto.GrantPermissionRequest
import com.qrorder.auth.dto.PermissionGrantResponse
import com.qrorder.auth.dto.ResetPasswordRequest
import com.qrorder.auth.dto.RevokePermissionRequest
import com.qrorder.auth.dto.UpdateUserRequest
import com.qrorder.auth.dto.UserResponse
import com.qrorder.auth.security.CurrentPrincipal
import com.qrorder.auth.service.PermissionService
import com.qrorder.auth.service.UserService
import com.qrorder.common.api.ApiResponse
import com.qrorder.common.api.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Quản lý user và permission (FR-USER-01..04, UC31, UC43, UC44). Chỉ ADMIN (SRS §10);
 * MANAGER/STAFF/KITCHEN nhận 403 - kể cả khi cố tự cấp role ADMIN (§12.4 #3).
 */
@Tag(name = "Users", description = "Quản lý tài khoản nhân viên, role và permission (ADMIN)")
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
class UserController(
    private val userService: UserService,
    private val permissionService: PermissionService,
) {
    @Operation(summary = "Danh sách user (lọc theo role, trạng thái, từ khóa username/họ tên; page từ 0)")
    @GetMapping
    fun list(
        @RequestParam(required = false) role: UserRole?,
        @RequestParam(required = false) status: UserStatus?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<UserResponse>> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE), Sort.by("id"))
        return ApiResponse.ok(userService.search(role, status, q, pageable))
    }

    @Operation(summary = "Chi tiết user")
    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): ApiResponse<UserResponse> = ApiResponse.ok(userService.get(id))

    @Operation(summary = "Tạo user (role ADMIN cần currentPassword + reason)")
    @PostMapping
    fun create(@Valid @RequestBody request: CreateUserRequest): ResponseEntity<ApiResponse<UserResponse>> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(userService.create(actorId(), request)))

    @Operation(summary = "Sửa họ tên (gửi kèm version)")
    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateUserRequest): ApiResponse<UserResponse> =
        ApiResponse.ok(userService.update(actorId(), id, request))

    @Operation(summary = "Khóa / ngừng dùng / mở lại tài khoản")
    @PatchMapping("/{id}/status")
    fun changeStatus(@PathVariable id: Long, @Valid @RequestBody request: ChangeUserStatusRequest): ApiResponse<UserResponse> =
        ApiResponse.ok(userService.changeStatus(actorId(), id, request))

    @Operation(summary = "Đổi role (xác thực lại bằng mật khẩu hiện tại + lý do; luôn còn >= 1 ADMIN)")
    @PatchMapping("/{id}/role")
    fun changeRole(@PathVariable id: Long, @Valid @RequestBody request: ChangeUserRoleRequest): ApiResponse<UserResponse> =
        ApiResponse.ok(userService.changeRole(actorId(), id, request))

    @Operation(summary = "Đặt lại mật khẩu cho user khác; thu hồi mọi refresh token của user")
    @PostMapping("/{id}/reset-password")
    fun resetPassword(@PathVariable id: Long, @Valid @RequestBody request: ResetPasswordRequest): ApiResponse<UserResponse> =
        ApiResponse.ok(userService.resetPassword(actorId(), id, request))

    @Operation(summary = "Danh sách bản cấp permission (includeRevoked=true để xem cả lịch sử)")
    @GetMapping("/{id}/permissions")
    fun listPermissions(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "false") includeRevoked: Boolean,
    ): ApiResponse<List<PermissionGrantResponse>> = ApiResponse.ok(permissionService.listGrants(id, includeRevoked))

    @Operation(summary = "Cấp permission (tự cấp cho chính mình cần currentPassword)")
    @PostMapping("/{id}/permissions")
    fun grantPermission(
        @PathVariable id: Long,
        @Valid @RequestBody request: GrantPermissionRequest,
    ): ResponseEntity<ApiResponse<PermissionGrantResponse>> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(permissionService.grant(actorId(), id, request)))

    @Operation(summary = "Thu hồi permission (tự thu hồi của chính mình cần currentPassword)")
    @PostMapping("/{id}/permissions/{permission}/revoke")
    fun revokePermission(
        @PathVariable id: Long,
        @PathVariable permission: Permission,
        @Valid @RequestBody request: RevokePermissionRequest,
    ): ApiResponse<PermissionGrantResponse> =
        ApiResponse.ok(permissionService.revoke(actorId(), id, permission, request))

    private fun actorId(): Long = CurrentPrincipal.requireStaff().userId

    companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
