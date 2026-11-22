package com.qrorder.notification.servicecall

import com.qrorder.auth.security.CurrentPrincipal
import com.qrorder.auth.security.GuestPrincipal
import com.qrorder.common.api.ApiResponse
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.notification.domain.ServiceCallStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Phân quyền (SRS §10): ADMIN không kế thừa quyền vận hành (BR-A06). */
object ServiceCallAccess {
    const val GUEST = "hasRole('GUEST')"
    const val FLOOR = "hasAnyRole('MANAGER','STAFF')"
}

private fun guest(): GuestPrincipal = CurrentPrincipal.get() as? GuestPrincipal ?: throw BusinessException(ErrorCode.ACCESS_DENIED)

/** FR-NOT-03: khách gọi nhân viên / yêu cầu thanh toán. Cần Guest JWT. */
@Tag(name = "Guest - Service calls", description = "Khách gọi nhân viên (CALL_STAFF) hoặc yêu cầu thanh toán (REQUEST_PAYMENT)")
@RestController
@RequestMapping("/api/v1/guest/service-calls")
class GuestServiceCallController(private val service: ServiceCallService) {

    @Operation(summary = "Gửi yêu cầu (mỗi loại tối đa một yêu cầu đang mở; gửi lại trả yêu cầu đang mở với 200)")
    @PostMapping
    @PreAuthorize(ServiceCallAccess.GUEST)
    fun create(@Valid @RequestBody request: GuestServiceCallRequest): ResponseEntity<ApiResponse<GuestServiceCallResponse>> {
        val result = service.guestCreate(guest(), request)
        return ResponseEntity.status(if (result.replayed) HttpStatus.OK else HttpStatus.CREATED).body(ApiResponse.ok(result))
    }

    @Operation(summary = "Các yêu cầu của phiên (mới trước)")
    @GetMapping
    @PreAuthorize(ServiceCallAccess.GUEST)
    fun list(): ApiResponse<List<GuestServiceCallResponse>> = ApiResponse.ok(service.guestList(guest().sessionId))
}

/** Staff/Manager: hàng đợi yêu cầu, nhận xử lý, hoàn tất; Staff gửi MANAGER_SUPPORT (§9.8). */
@Tag(name = "Staff - Service calls", description = "Hàng đợi yêu cầu hỗ trợ, nhận xử lý (ACKNOWLEDGED), hoàn tất (RESOLVED)")
@RestController
@RequestMapping("/api/v1/service-calls")
class StaffServiceCallController(private val service: ServiceCallService) {

    @Operation(summary = "Hàng đợi yêu cầu (mặc định OPEN + ACKNOWLEDGED, cũ trước)")
    @GetMapping
    @PreAuthorize(ServiceCallAccess.FLOOR)
    fun queue(@RequestParam(required = false) status: List<ServiceCallStatus>?): ApiResponse<List<ServiceCallResponse>> =
        ApiResponse.ok(service.queue(status))

    @Operation(summary = "Staff gửi Manager hỗ trợ cho một phiên (MANAGER_SUPPORT)")
    @PostMapping
    @PreAuthorize(ServiceCallAccess.FLOOR)
    fun create(@Valid @RequestBody request: StaffServiceCallRequest): ResponseEntity<ApiResponse<ServiceCallResponse>> {
        val (call, existed) = service.staffCreate(CurrentPrincipal.requireStaff(), request)
        return ResponseEntity.status(if (existed) HttpStatus.OK else HttpStatus.CREATED).body(ApiResponse.ok(call))
    }

    @Operation(summary = "Nhận xử lý (OPEN -> ACKNOWLEDGED). MANAGER_SUPPORT chỉ Manager")
    @PostMapping("/{id}/acknowledge")
    @PreAuthorize(ServiceCallAccess.FLOOR)
    fun acknowledge(@PathVariable id: Long): ApiResponse<ServiceCallResponse> =
        ApiResponse.ok(service.acknowledge(CurrentPrincipal.requireStaff(), id))

    @Operation(summary = "Hoàn tất (-> RESOLVED). MANAGER_SUPPORT chỉ Manager")
    @PostMapping("/{id}/resolve")
    @PreAuthorize(ServiceCallAccess.FLOOR)
    fun resolve(@PathVariable id: Long, @Valid @RequestBody(required = false) request: ResolveServiceCallRequest?): ApiResponse<ServiceCallResponse> =
        ApiResponse.ok(service.resolve(CurrentPrincipal.requireStaff(), id, request))
}
