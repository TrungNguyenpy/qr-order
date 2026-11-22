package com.qrorder.auth.api

import com.qrorder.auth.dto.ChangePasswordRequest
import com.qrorder.auth.dto.CurrentUserResponse
import com.qrorder.auth.dto.LoginRequest
import com.qrorder.auth.dto.LogoutRequest
import com.qrorder.auth.dto.RefreshTokenRequest
import com.qrorder.auth.dto.TokenResponse
import com.qrorder.auth.security.CurrentPrincipal
import com.qrorder.auth.service.AuthService
import com.qrorder.common.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Xác thực nhân viên (FR-AUTH-01, FR-AUTH-02, FR-USER-02).
 *
 * Hợp đồng cho client (Admin Dashboard, Staff App):
 * - login/refresh/logout là endpoint public; mọi lỗi của login/refresh là 401 -> chuyển về màn hình đăng nhập.
 *   Interceptor tự refresh khi gặp 401 KHÔNG được áp dụng cho chính các endpoint /api/v1/auth (login, refresh, logout).
 * - Tuần tự hóa refresh: tại một thời điểm chỉ một request refresh (BE-06).
 */
@Tag(name = "Auth", description = "Đăng nhập, refresh token, đăng xuất, tài khoản hiện tại")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
) {
    @Operation(summary = "Đăng nhập nhân viên, trả access token + refresh token")
    @SecurityRequirements
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ApiResponse<TokenResponse> =
        ApiResponse.ok(authService.login(request))

    @Operation(summary = "Đổi refresh token lấy cặp token mới (rotation)")
    @SecurityRequirements
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshTokenRequest): ApiResponse<TokenResponse> =
        ApiResponse.ok(authService.refresh(requireNotNull(request.refreshToken)))

    @Operation(summary = "Đăng xuất: thu hồi phiên đăng nhập (family) của refresh token")
    @SecurityRequirements
    @PostMapping("/logout")
    fun logout(@Valid @RequestBody request: LogoutRequest): ApiResponse<Unit> {
        authService.logout(requireNotNull(request.refreshToken))
        return ApiResponse.okEmpty()
    }

    @Operation(summary = "Thông tin tài khoản hiện tại kèm permission hiệu lực")
    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'KITCHEN')") // MANAGER gồm STAFF theo role hierarchy
    fun me(): ApiResponse<CurrentUserResponse> =
        ApiResponse.ok(authService.me(CurrentPrincipal.requireStaff().userId))

    @Operation(summary = "Tự đổi mật khẩu; mọi refresh token bị thu hồi, cần đăng nhập lại")
    @PostMapping("/change-password")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'KITCHEN')")
    fun changePassword(@Valid @RequestBody request: ChangePasswordRequest): ApiResponse<Unit> {
        authService.changePassword(CurrentPrincipal.requireStaff().userId, request)
        return ApiResponse.okEmpty()
    }
}
