package com.qrorder.common.settings

import com.qrorder.auth.security.CurrentPrincipal
import com.qrorder.common.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** FR-CFG-01: Admin xem và sửa restaurant_settings (UC35). */
@Tag(name = "Settings", description = "Cấu hình nhà hàng (restaurant_settings)")
@RestController
@RequestMapping("/api/v1/settings")
@PreAuthorize("hasRole('ADMIN')")
class RestaurantSettingsController(
    private val service: RestaurantSettingsService,
) {
    @Operation(summary = "Xem cấu hình nhà hàng")
    @GetMapping
    fun get(): ApiResponse<RestaurantSettingsResponse> = ApiResponse.ok(service.get())

    @Operation(summary = "Cập nhật cấu hình (gửi kèm version đã đọc; lệch version trả 409)")
    @PutMapping
    fun update(@Valid @RequestBody request: UpdateRestaurantSettingsRequest): ApiResponse<RestaurantSettingsResponse> =
        ApiResponse.ok(service.update(CurrentPrincipal.requireStaff().userId, request))
}
