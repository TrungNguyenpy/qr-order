package com.qrorder.billing.api

import com.qrorder.auth.security.CurrentPrincipal
import com.qrorder.auth.security.GuestPrincipal
import com.qrorder.billing.dto.BillResponse
import com.qrorder.billing.dto.ConfirmPaymentRequest
import com.qrorder.billing.dto.CreateBillRequest
import com.qrorder.billing.dto.GuestBillResponse
import com.qrorder.billing.dto.PaymentDetailResponse
import com.qrorder.billing.dto.PaymentReceiptResponse
import com.qrorder.billing.dto.PaymentResponse
import com.qrorder.billing.dto.PaymentResultResponse
import com.qrorder.billing.dto.RecordPaymentRequest
import com.qrorder.billing.dto.ReopenSessionRequest
import com.qrorder.billing.dto.ReopenSessionResponse
import com.qrorder.billing.dto.UpdateBillDiscountRequest
import com.qrorder.billing.dto.BillingAttentionItem
import com.qrorder.billing.dto.ConfirmRefundRequest
import com.qrorder.billing.dto.RecordRefundRequest
import com.qrorder.billing.dto.RefundDetailResponse
import com.qrorder.billing.dto.RefundReceiptResponse
import com.qrorder.billing.dto.RefundResponse
import com.qrorder.billing.dto.RefundResultResponse
import com.qrorder.billing.dto.RequestVoidRequest
import com.qrorder.billing.dto.VoidResultResponse
import com.qrorder.billing.dto.WithdrawVoidRequest
import com.qrorder.billing.service.RefundService
import com.qrorder.billing.service.VoidService
import com.qrorder.billing.service.BillService
import com.qrorder.billing.service.PaymentService
import com.qrorder.billing.service.ReceiptService
import com.qrorder.billing.service.SessionReopenService
import com.qrorder.common.api.ApiResponse
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Phân quyền (SRS §10): nghiệp vụ tài chính qua user_permission (MANAGER mặc định có); ADMIN không kế thừa (O3). */
object BillingAccess {
    /** Đọc Bill/giao dịch/phiếu: nhân viên sảnh và Manager. */
    const val FLOOR = "hasAnyRole('MANAGER','STAFF')"
    /** BR-P05, FR-PAY-04, FR-PAY-06: Tính tiền, thu tiền, xác nhận giao dịch, mở lại phiên. */
    const val PAYMENT_PROCESS = "@perm.has('PAYMENT_PROCESS')"
    /** BR-P07: giảm giá (kiểm tra thêm trong service khi tạo Bill kèm giảm giá). */
    const val BILL_DISCOUNT = "@perm.has('BILL_DISCOUNT')"
    const val GUEST = "hasRole('GUEST')"
    /** GĐ5b - BR-P09, BR-P14, BR-P16: Void, rút Void, Bill thay thế. */
    const val BILL_VOID = "@perm.has('BILL_VOID')"
    /** GĐ5b - BR-P13: ghi nhận / xác nhận hoàn tiền. */
    const val REFUND_RECORD = "@perm.has('REFUND_RECORD')"
    /** FR-PAY-06: luồng (a) cần PAYMENT_PROCESS, luồng (b) sau Void cần SESSION_REOPEN - service kiểm tra từng luồng. */
    const val REOPEN = "@perm.has('PAYMENT_PROCESS') or @perm.has('SESSION_REOPEN')"
}

/** Tính tiền theo Order (FR-PAY-01..03, FR-PAY-05). */
@Tag(name = "Staff - Billing", description = "Tính tiền (tạo Bill), giảm giá, xem Bill")
@RestController
@RequestMapping("/api/v1")
class BillController(private val bills: BillService) {

    @Operation(
        summary = "Tính tiền: tạo Bill ACTIVE, khóa Order, phiên BILLING, bàn PAYMENT_PENDING",
        description = "422 VAT_NOT_CONFIGURED, 409 BATCH_PENDING / NO_BILLABLE_ITEMS / BILL_ALREADY_ACTIVE / ORDER_NOT_OPEN. " +
            "discount (tùy chọn) cần thêm quyền BILL_DISCOUNT.",
    )
    @PostMapping("/orders/{orderId}/bill")
    @PreAuthorize(BillingAccess.PAYMENT_PROCESS)
    fun create(
        @PathVariable orderId: Long,
        @Valid @RequestBody(required = false) request: CreateBillRequest?,
    ): ResponseEntity<ApiResponse<BillResponse>> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(bills.create(CurrentPrincipal.requireStaff(), orderId, request ?: CreateBillRequest())))

    @Operation(summary = "Bill hiện hành của Order (hoặc Bill gần nhất)")
    @GetMapping("/orders/{orderId}/bill")
    @PreAuthorize(BillingAccess.FLOOR)
    fun latest(@PathVariable orderId: Long): ApiResponse<BillResponse> = ApiResponse.ok(bills.latestByOrder(orderId))

    @Operation(summary = "Lịch sử mọi Bill của Order (kể cả VOIDED / WRITTEN_OFF)")
    @GetMapping("/orders/{orderId}/bills")
    @PreAuthorize(BillingAccess.FLOOR)
    fun history(@PathVariable orderId: Long): ApiResponse<List<BillResponse>> = ApiResponse.ok(bills.historyByOrder(orderId))

    @Operation(summary = "Chi tiết Bill (số liệu snapshot, món tính tiền, giao dịch)")
    @GetMapping("/bills/{billId}")
    @PreAuthorize(BillingAccess.FLOOR)
    fun detail(@PathVariable billId: Long): ApiResponse<BillResponse> = ApiResponse.ok(bills.byId(billId))

    @Operation(
        summary = "Đổi giảm giá = phát hành lại Bill (Bill cũ VOIDED, Bill mới thay thế) - BE-29",
        description = "Chỉ khi Bill ACTIVE chưa có Payment PENDING/SUCCESS. type NONE = gỡ giảm giá. Lý do bắt buộc.",
    )
    @PutMapping("/bills/{billId}/discount")
    @PreAuthorize(BillingAccess.BILL_DISCOUNT)
    fun updateDiscount(@PathVariable billId: Long, @Valid @RequestBody request: UpdateBillDiscountRequest): ApiResponse<BillResponse> =
        ApiResponse.ok(bills.updateDiscount(CurrentPrincipal.requireStaff(), billId, request))
}

/** Thu tiền (FR-PAY-04, BR-P10..P12) và phiếu thanh toán nội bộ (FR-PAY-12). */
@Tag(name = "Staff - Payments", description = "Ghi nhận / xác nhận thanh toán, lịch sử, phiếu thanh toán nội bộ")
@RestController
@RequestMapping("/api/v1")
class PaymentController(
    private val payments: PaymentService,
    private val receipts: ReceiptService,
) {
    @Operation(
        summary = "Ghi nhận thanh toán (SUCCESS hoặc PENDING); idempotent theo requestId",
        description = "201 giao dịch mới, 200 khi gửi lại cùng requestId. 422 PAYMENT_EXCEEDS_REMAINING, 409 PAYMENT_BILL_NOT_ACTIVE.",
    )
    @PostMapping("/bills/{billId}/payments")
    @PreAuthorize(BillingAccess.PAYMENT_PROCESS)
    fun record(@PathVariable billId: Long, @Valid @RequestBody request: RecordPaymentRequest): ResponseEntity<ApiResponse<PaymentResultResponse>> {
        val result = payments.record(CurrentPrincipal.requireStaff().userId, billId, request)
        return ResponseEntity.status(if (result.replayed) HttpStatus.OK else HttpStatus.CREATED).body(ApiResponse.ok(result))
    }

    @Operation(summary = "Giao dịch của Bill")
    @GetMapping("/bills/{billId}/payments")
    @PreAuthorize(BillingAccess.FLOOR)
    fun list(@PathVariable billId: Long): ApiResponse<List<PaymentResponse>> = ApiResponse.ok(payments.listByBill(billId))

    @Operation(summary = "Chi tiết giao dịch kèm lịch sử trạng thái")
    @GetMapping("/payments/{paymentId}")
    @PreAuthorize(BillingAccess.FLOOR)
    fun detail(@PathVariable paymentId: Long): ApiResponse<PaymentDetailResponse> = ApiResponse.ok(payments.detail(paymentId))

    @Operation(summary = "Xác nhận giao dịch PENDING -> SUCCESS (cấp mã phiếu) hoặc FAILED (bắt buộc lý do)")
    @PostMapping("/payments/{paymentId}/confirm")
    @PreAuthorize(BillingAccess.PAYMENT_PROCESS)
    fun confirm(@PathVariable paymentId: Long, @Valid @RequestBody request: ConfirmPaymentRequest): ApiResponse<PaymentResultResponse> =
        ApiResponse.ok(payments.confirm(CurrentPrincipal.requireStaff().userId, paymentId, request))

    @Operation(summary = "Dữ liệu in phiếu thanh toán nội bộ YYYYMMDD-NNNN (chỉ giao dịch SUCCESS)")
    @GetMapping("/payments/{paymentId}/receipt")
    @PreAuthorize(BillingAccess.FLOOR)
    fun receipt(@PathVariable paymentId: Long): ApiResponse<PaymentReceiptResponse> = ApiResponse.ok(receipts.paymentReceipt(paymentId))
}

/** Mở lại phiên BILLING (FR-PAY-06): (a) Staff khi Bill chưa có tiền thu (P1, BR-P08 a); (b) Manager sau Void (BR-P08 b). */
@Tag(name = "Staff - Sessions (billing)", description = "Mở lại phiên BILLING: Bill chưa có tiền thu, hoặc sau khi Manager Void")
@RestController
@RequestMapping("/api/v1/sessions")
class SessionReopenController(private val reopenService: SessionReopenService) {

    @Operation(
        summary = "Mở lại phiên: Order OPEN, phiên OPEN, bàn OCCUPIED",
        description = "(a) Bill ACTIVE chưa có Payment SUCCESS/PENDING: PAYMENT_PROCESS, Bill VOIDED (SESSION_REOPEN). " +
            "(b) Bill đã VOIDED do Manager Void, Order chờ Bill thay thế: SESSION_REOPEN.",
    )
    @PostMapping("/{sessionId}/reopen")
    @PreAuthorize(BillingAccess.REOPEN)
    fun reopen(@PathVariable sessionId: Long, @Valid @RequestBody request: ReopenSessionRequest): ApiResponse<ReopenSessionResponse> =
        ApiResponse.ok(reopenService.reopen(CurrentPrincipal.requireStaff(), sessionId, request))
}

/** GĐ5b: Void có hoàn tiền, rút Void, Bill thay thế, danh sách Cần xử lý (BR-P09, BR-P14, BR-P16, BR-P17). */
@Tag(name = "Manager - Void & Bill thay thế", description = "Yêu cầu Void, rút Void, tạo Bill thay thế, danh sách Cần xử lý")
@RestController
@RequestMapping("/api/v1")
class VoidController(
    private val voids: VoidService,
    private val bills: BillService,
) {
    @Operation(
        summary = "Yêu cầu Void (BILL_VOID): chưa có tiền thu -> VOIDED ngay; có tiền thu -> VOID_PENDING chờ hoàn tiền",
        description = "409 PENDING_PAYMENT_EXISTS khi còn Payment PENDING; 409 BILL_NOT_ACTIVE / BILL_VOID_PENDING. Áp dụng cả khi phiên đã CLOSED.",
    )
    @PostMapping("/bills/{billId}/void")
    @PreAuthorize(BillingAccess.BILL_VOID)
    fun requestVoid(@PathVariable billId: Long, @Valid @RequestBody request: RequestVoidRequest): ApiResponse<VoidResultResponse> =
        ApiResponse.ok(voids.requestVoid(CurrentPrincipal.requireStaff().userId, billId, request))

    @Operation(summary = "Rút yêu cầu Void (BILL_VOID): chỉ khi không có Refund PENDING/SUCCESS - 409 VOID_HAS_REFUNDS")
    @PostMapping("/bills/{billId}/void/withdraw")
    @PreAuthorize(BillingAccess.BILL_VOID)
    fun withdraw(@PathVariable billId: Long, @Valid @RequestBody request: WithdrawVoidRequest): ApiResponse<VoidResultResponse> =
        ApiResponse.ok(voids.withdrawVoid(CurrentPrincipal.requireStaff().userId, billId, request))

    @Operation(
        summary = "Tạo Bill thay thế (BILL_VOID) cho Order LOCKED chờ Bill thay thế; discount tùy chọn cần BILL_DISCOUNT",
        description = "409 REPLACEMENT_BILL_NOT_ALLOWED / BILL_VOID_PENDING / BILL_ALREADY_ACTIVE / BATCH_PENDING / NO_BILLABLE_ITEMS, 422 VAT_NOT_CONFIGURED.",
    )
    @PostMapping("/orders/{orderId}/replacement-bill")
    @PreAuthorize(BillingAccess.BILL_VOID)
    fun replacement(
        @PathVariable orderId: Long,
        @Valid @RequestBody(required = false) request: CreateBillRequest?,
    ): ResponseEntity<ApiResponse<BillResponse>> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(bills.createReplacement(CurrentPrincipal.requireStaff(), orderId, request ?: CreateBillRequest())))

    @Operation(summary = "Danh sách Cần xử lý: Bill chờ hoàn tiền và Order chờ Bill thay thế (Staff chỉ xem)")
    @GetMapping("/billing/attention")
    @PreAuthorize(BillingAccess.FLOOR)
    fun attention(): ApiResponse<List<BillingAttentionItem>> = ApiResponse.ok(voids.attention())
}

/** GĐ5b: hoàn tiền (BR-P13, FR-PAY-08) và phiếu hoàn tiền nội bộ RF-... (FR-PAY-12). */
@Tag(name = "Manager - Refunds", description = "Ghi nhận / xác nhận hoàn tiền cho Bill VOID_PENDING, lịch sử, phiếu hoàn tiền")
@RestController
@RequestMapping("/api/v1")
class RefundController(private val refunds: RefundService) {

    @Operation(
        summary = "Ghi nhận hoàn tiền (REFUND_RECORD) - SUCCESS chỉ khi tiền đã trả cho khách; idempotent theo requestId",
        description = "201 mới / 200 gửi lại. 409 REFUND_REQUIRES_VOID_PENDING; 422 REFUND_EXCEEDS_REFUNDABLE (vượt TPS - TRS - TRP).",
    )
    @PostMapping("/bills/{billId}/refunds")
    @PreAuthorize(BillingAccess.REFUND_RECORD)
    fun record(@PathVariable billId: Long, @Valid @RequestBody request: RecordRefundRequest): ResponseEntity<ApiResponse<RefundResultResponse>> {
        val result = refunds.record(CurrentPrincipal.requireStaff().userId, billId, request)
        return ResponseEntity.status(if (result.replayed) HttpStatus.OK else HttpStatus.CREATED).body(ApiResponse.ok(result))
    }

    @Operation(summary = "Xác nhận hoàn tiền PENDING -> SUCCESS (cấp mã RF-...) hoặc FAILED (bắt buộc lý do)")
    @PostMapping("/refunds/{refundId}/confirm")
    @PreAuthorize(BillingAccess.REFUND_RECORD)
    fun confirm(@PathVariable refundId: Long, @Valid @RequestBody request: ConfirmRefundRequest): ApiResponse<RefundResultResponse> =
        ApiResponse.ok(refunds.confirm(CurrentPrincipal.requireStaff().userId, refundId, request))

    @Operation(summary = "Giao dịch hoàn tiền của Bill")
    @GetMapping("/bills/{billId}/refunds")
    @PreAuthorize(BillingAccess.FLOOR)
    fun list(@PathVariable billId: Long): ApiResponse<List<RefundResponse>> = ApiResponse.ok(refunds.listByBill(billId))

    @Operation(summary = "Chi tiết giao dịch hoàn tiền kèm lịch sử trạng thái")
    @GetMapping("/refunds/{refundId}")
    @PreAuthorize(BillingAccess.FLOOR)
    fun detail(@PathVariable refundId: Long): ApiResponse<RefundDetailResponse> = ApiResponse.ok(refunds.detail(refundId))

    @Operation(summary = "Dữ liệu in phiếu hoàn tiền nội bộ RF-YYYYMMDD-NNNN (chỉ Refund SUCCESS)")
    @GetMapping("/refunds/{refundId}/receipt")
    @PreAuthorize(BillingAccess.FLOOR)
    fun receipt(@PathVariable refundId: Long): ApiResponse<RefundReceiptResponse> = ApiResponse.ok(refunds.receipt(refundId))
}

/** Phiếu tạm tính cho khách (UC10). */
@Tag(name = "Guest - Bill", description = "Khách xem phiếu tạm tính của phiên")
@RestController
@RequestMapping("/api/v1/guest/bill")
class GuestBillController(private val bills: BillService) {

    @Operation(summary = "Phiếu tạm tính hiện tại (404 khi nhân viên chưa tính tiền)")
    @GetMapping
    @PreAuthorize(BillingAccess.GUEST)
    fun current(): ApiResponse<GuestBillResponse> {
        val guest = CurrentPrincipal.get() as? GuestPrincipal ?: throw BusinessException(ErrorCode.ACCESS_DENIED)
        return ApiResponse.ok(bills.guestBill(guest.sessionId))
    }
}
