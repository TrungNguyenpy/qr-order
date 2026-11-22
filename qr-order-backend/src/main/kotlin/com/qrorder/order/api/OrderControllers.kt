package com.qrorder.order.api

import com.qrorder.auth.security.CurrentPrincipal
import com.qrorder.auth.security.GuestPrincipal
import com.qrorder.common.api.ApiResponse
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.dto.ApproveCancelRequestRequest
import com.qrorder.order.dto.ChangeItemQuantityRequest
import com.qrorder.order.dto.StaffCancelItemRequest
import com.qrorder.order.dto.StaffPlaceOrderResponse
import com.qrorder.order.dto.CancelRequestResponse
import com.qrorder.order.dto.ChangeItemStatusRequest
import com.qrorder.order.dto.ConfirmBatchRequest
import com.qrorder.order.dto.CreateCancelRequestRequest
import com.qrorder.order.dto.DenyCancelRequestRequest
import com.qrorder.order.dto.GuestCancelItemRequest
import com.qrorder.order.dto.GuestOrderResponse
import com.qrorder.order.dto.ItemActionResponse
import com.qrorder.order.dto.KitchenItemResponse
import com.qrorder.order.dto.KitchenRejectItemRequest
import com.qrorder.order.dto.PlaceOrderRequest
import com.qrorder.order.dto.RejectBatchRequest
import com.qrorder.order.dto.StaffBatchResponse
import com.qrorder.order.dto.StaffOrderResponse
import com.qrorder.order.service.OrderBatchService
import com.qrorder.order.service.OrderItemService
import com.qrorder.order.service.OrderPlacementService
import com.qrorder.order.service.OrderQueryService
import com.qrorder.order.service.StaffOrderService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Phân quyền (SRS §10): ADMIN không kế thừa quyền vận hành (BR-A06). */
object OrderAccess {
    const val GUEST = "hasRole('GUEST')"
    /** Staff sảnh: nhận/xác nhận đợt, phục vụ món. */
    const val FLOOR = "hasAnyRole('MANAGER','STAFF')"
    /** Bếp: KDS, nấu, từ chối món. */
    const val KITCHEN = "hasAnyRole('MANAGER','KITCHEN')"
    /** BR-O06: duyệt/từ chối yêu cầu hủy món đang nấu. */
    const val CANCEL_APPROVE = "@perm.has('ITEM_CANCEL_APPROVE')"
}

private fun guest(): GuestPrincipal = CurrentPrincipal.get() as? GuestPrincipal ?: throw BusinessException(ErrorCode.ACCESS_DENIED)

/** FR-ORD-01..05: khách đặt món, xem order, hủy món. Cần Guest JWT. */
@Tag(name = "Guest - Orders", description = "Khách gửi đợt order, xem order của phiên, hủy món")
@RestController
@RequestMapping("/api/v1/guest/orders")
class GuestOrderController(
    private val placement: OrderPlacementService,
    private val query: OrderQueryService,
    private val items: OrderItemService,
) {
    @Operation(summary = "Gửi đợt order (idempotent theo clientRequestId; 429 khi vượt order_rate_limit_per_minute)")
    @PostMapping
    @PreAuthorize(OrderAccess.GUEST)
    fun place(@Valid @RequestBody request: PlaceOrderRequest): ResponseEntity<ApiResponse<GuestOrderResponse>> {
        val result = placement.place(guest(), request)
        // Gửi lại cùng clientRequestId: 200 với kết quả cũ; đợt mới: 201.
        return ResponseEntity.status(if (result.replayed) HttpStatus.OK else HttpStatus.CREATED).body(ApiResponse.ok(result))
    }

    @Operation(summary = "Order hiện tại của phiên (các đợt, món, trạng thái, tổng tiền món tính tiền)")
    @GetMapping
    @PreAuthorize(OrderAccess.GUEST)
    fun current(): ApiResponse<GuestOrderResponse> = ApiResponse.ok(query.guestOrder(guest().sessionId))

    @Operation(summary = "Hủy món PENDING/CONFIRMED khi Order còn OPEN")
    @PostMapping("/items/{itemId}/cancel")
    @PreAuthorize(OrderAccess.GUEST)
    fun cancel(@PathVariable itemId: Long, @Valid @RequestBody(required = false) request: GuestCancelItemRequest?): ApiResponse<ItemActionResponse> =
        ApiResponse.ok(items.guestCancel(guest(), itemId, request?.note))

    @Operation(summary = "Gửi yêu cầu hủy món PREPARING/READY (Manager duyệt - BR-O06)")
    @PostMapping("/items/{itemId}/cancel-requests")
    @PreAuthorize(OrderAccess.GUEST)
    fun requestCancel(
        @PathVariable itemId: Long,
        @Valid @RequestBody(required = false) request: CreateCancelRequestRequest?,
    ): ResponseEntity<ApiResponse<CancelRequestResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(items.requestCancel(guest(), itemId, request?.reason)))
}

/** Staff sảnh: hàng đợi đợt chờ xác nhận, claim, xác nhận/từ chối, phục vụ món (FR-ORD-06). */
@Tag(name = "Staff - Orders", description = "Hàng đợi đợt order, claim, xác nhận/từ chối, phục vụ món, yêu cầu hủy")
@RestController
@RequestMapping("/api/v1/orders")
class StaffOrderController(
    private val batches: OrderBatchService,
    private val items: OrderItemService,
    private val staffOrders: StaffOrderService,
    private val query: OrderQueryService,
) {
    @Operation(summary = "Đợt chờ xác nhận (cũ trước, kèm người đang giữ claim)")
    @GetMapping("/batches/pending")
    @PreAuthorize(OrderAccess.FLOOR)
    fun pending(): ApiResponse<List<StaffBatchResponse>> = ApiResponse.ok(query.pendingBatches())

    @Operation(summary = "Order của một phiên bàn")
    @GetMapping("/sessions/{sessionId}")
    @PreAuthorize(OrderAccess.FLOOR)
    fun bySession(@PathVariable sessionId: Long): ApiResponse<StaffOrderResponse> = ApiResponse.ok(query.staffOrder(sessionId))

    @Operation(summary = "Nhận xử lý đợt (claim, TTL batch_claim_ttl_seconds; Manager giành quyền được) - 409 kèm tên người đang xử lý")
    @PostMapping("/batches/{id}/claim")
    @PreAuthorize(OrderAccess.FLOOR)
    fun claim(@PathVariable id: Long): ApiResponse<StaffBatchResponse> {
        val staff = CurrentPrincipal.requireStaff()
        return ApiResponse.ok(batches.claim(staff.userId, staff.role, id))
    }

    @Operation(summary = "Nhả claim của chính mình")
    @PostMapping("/batches/{id}/release")
    @PreAuthorize(OrderAccess.FLOOR)
    fun release(@PathVariable id: Long): ApiResponse<StaffBatchResponse> =
        ApiResponse.ok(batches.release(CurrentPrincipal.requireStaff().userId, id))

    @Operation(summary = "Xác nhận đợt (tùy chọn từ chối một số món -> PARTIALLY_ACCEPTED)")
    @PostMapping("/batches/{id}/confirm")
    @PreAuthorize(OrderAccess.FLOOR)
    fun confirm(@PathVariable id: Long, @Valid @RequestBody(required = false) request: ConfirmBatchRequest?): ApiResponse<StaffBatchResponse> {
        val staff = CurrentPrincipal.requireStaff()
        return ApiResponse.ok(batches.confirm(staff.userId, staff.role, id, request ?: ConfirmBatchRequest()))
    }

    @Operation(summary = "Từ chối cả đợt (reasonCode bắt buộc)")
    @PostMapping("/batches/{id}/reject")
    @PreAuthorize(OrderAccess.FLOOR)
    fun reject(@PathVariable id: Long, @Valid @RequestBody request: RejectBatchRequest): ApiResponse<StaffBatchResponse> {
        val staff = CurrentPrincipal.requireStaff()
        return ApiResponse.ok(batches.reject(staff.userId, staff.role, id, request))
    }

    @Operation(summary = "FR-ORD-05: nhân viên đặt món hộ khách (đợt tự CONFIRMED, không rate limit/PIN; 201 mới, 200 nếu lặp clientRequestId)")
    @PostMapping("/sessions/{sessionId}/batches")
    @PreAuthorize(OrderAccess.FLOOR)
    fun placeForGuest(@PathVariable sessionId: Long, @Valid @RequestBody request: PlaceOrderRequest): ResponseEntity<ApiResponse<StaffPlaceOrderResponse>> {
        val result = staffOrders.place(CurrentPrincipal.requireStaff().userId, sessionId, request)
        return ResponseEntity.status(if (result.replayed) HttpStatus.OK else HttpStatus.CREATED).body(ApiResponse.ok(result))
    }

    @Operation(summary = "FR-ORD-05: đổi số lượng món PENDING/CONFIRMED (khóa lạc quan theo version)")
    @PatchMapping("/items/{itemId}/quantity")
    @PreAuthorize(OrderAccess.FLOOR)
    fun changeQuantity(@PathVariable itemId: Long, @Valid @RequestBody request: ChangeItemQuantityRequest): ApiResponse<ItemActionResponse> =
        ApiResponse.ok(items.staffChangeQuantity(CurrentPrincipal.requireStaff().userId, itemId, requireNotNull(request.quantity), requireNotNull(request.version)))

    @Operation(summary = "FR-ORD-05: nhân viên hủy trực tiếp món PENDING/CONFIRMED kèm lý do (PREPARING/READY phải qua Manager duyệt)")
    @PostMapping("/items/{itemId}/cancel")
    @PreAuthorize(OrderAccess.FLOOR)
    fun cancelItem(@PathVariable itemId: Long, @Valid @RequestBody request: StaffCancelItemRequest): ApiResponse<ItemActionResponse> =
        ApiResponse.ok(items.staffCancel(CurrentPrincipal.requireStaff().userId, itemId, requireNotNull(request.reason)))

    @Operation(summary = "Món READY chờ mang ra bàn (cũ trước)")
    @GetMapping("/items/ready")
    @PreAuthorize(OrderAccess.FLOOR)
    fun ready(): ApiResponse<List<KitchenItemResponse>> = ApiResponse.ok(query.readyItems())

    @Operation(summary = "Phục vụ món: READY -> SERVED")
    @PostMapping("/items/{itemId}/serve")
    @PreAuthorize(OrderAccess.FLOOR)
    fun serve(@PathVariable itemId: Long): ApiResponse<ItemActionResponse> =
        ApiResponse.ok(items.serve(CurrentPrincipal.requireStaff().userId, itemId))

    @Operation(summary = "Yêu cầu hủy món đang nấu chờ duyệt (ITEM_CANCEL_APPROVE)")
    @GetMapping("/cancel-requests")
    @PreAuthorize(OrderAccess.CANCEL_APPROVE)
    fun cancelRequests(): ApiResponse<List<CancelRequestResponse>> = ApiResponse.ok(query.pendingCancelRequests())

    @Operation(summary = "Duyệt yêu cầu hủy: món CANCELLED, wasted = true (ITEM_CANCEL_APPROVE, ghi audit)")
    @PostMapping("/cancel-requests/{id}/approve")
    @PreAuthorize(OrderAccess.CANCEL_APPROVE)
    fun approve(@PathVariable id: Long, @Valid @RequestBody(required = false) request: ApproveCancelRequestRequest?): ApiResponse<CancelRequestResponse> =
        ApiResponse.ok(items.approveCancel(CurrentPrincipal.requireStaff().userId, id, request?.note))

    @Operation(summary = "Từ chối yêu cầu hủy (lý do bắt buộc, ghi audit)")
    @PostMapping("/cancel-requests/{id}/deny")
    @PreAuthorize(OrderAccess.CANCEL_APPROVE)
    fun deny(@PathVariable id: Long, @Valid @RequestBody request: DenyCancelRequestRequest): ApiResponse<CancelRequestResponse> =
        ApiResponse.ok(items.denyCancel(CurrentPrincipal.requireStaff().userId, id, requireNotNull(request.reason)))
}

/** Bếp (KDS): FR-KIT-01..03. */
@Tag(name = "Kitchen", description = "Màn hình bếp: món chờ/đang nấu, đổi trạng thái, từ chối món")
@RestController
@RequestMapping("/api/v1/kitchen")
class KitchenController(
    private val items: OrderItemService,
    private val query: OrderQueryService,
) {
    @Operation(summary = "Món chờ/đang nấu (mặc định CONFIRMED,PREPARING; lọc ?status=...), cũ trước")
    @GetMapping("/items")
    @PreAuthorize(OrderAccess.KITCHEN)
    fun list(@RequestParam(required = false) status: List<OrderItemStatus>?): ApiResponse<List<KitchenItemResponse>> {
        val statuses = status?.takeIf { it.isNotEmpty() }?.toSet() ?: setOf(OrderItemStatus.CONFIRMED, OrderItemStatus.PREPARING)
        return ApiResponse.ok(query.kitchenItems(statuses))
    }

    @Operation(summary = "Bếp đổi trạng thái món: CONFIRMED -> PREPARING -> READY")
    @PostMapping("/items/{itemId}/status")
    @PreAuthorize(OrderAccess.KITCHEN)
    fun changeStatus(@PathVariable itemId: Long, @Valid @RequestBody request: ChangeItemStatusRequest): ApiResponse<ItemActionResponse> =
        ApiResponse.ok(items.advanceInKitchen(CurrentPrincipal.requireStaff().userId, itemId, requireNotNull(request.status)))

    @Operation(summary = "Bếp từ chối món CONFIRMED/PREPARING (reasonCode bắt buộc; PREPARING -> wasted)")
    @PostMapping("/items/{itemId}/reject")
    @PreAuthorize(OrderAccess.KITCHEN)
    fun reject(@PathVariable itemId: Long, @Valid @RequestBody request: KitchenRejectItemRequest): ApiResponse<ItemActionResponse> =
        ApiResponse.ok(items.kitchenReject(CurrentPrincipal.requireStaff().userId, itemId, requireNotNull(request.reasonCode), request.reasonNote))
}
