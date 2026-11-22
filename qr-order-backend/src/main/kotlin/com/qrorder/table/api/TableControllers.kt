package com.qrorder.table.api

import com.qrorder.auth.security.CurrentPrincipal
import com.qrorder.auth.security.GuestPrincipal
import com.qrorder.common.api.ApiResponse
import com.qrorder.common.api.PageResponse
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.web.ClientRequestInfo
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.domain.TableStatus
import com.qrorder.table.dto.ChangeTableActiveRequest
import com.qrorder.table.dto.ChangeTableStatusRequest
import com.qrorder.table.dto.CreateTableRequest
import com.qrorder.table.dto.FloorPlanTableResponse
import com.qrorder.table.dto.ForceCloseSessionRequest
import com.qrorder.table.dto.GuestSessionResponse
import com.qrorder.table.dto.GuestTokenResponse
import com.qrorder.table.dto.JoinSessionRequest
import com.qrorder.table.dto.QrCodeResponse
import com.qrorder.table.dto.RevokeQrRequest
import com.qrorder.table.dto.RotateQrRequest
import com.qrorder.table.dto.StaffSessionResponse
import com.qrorder.table.dto.TableResponse
import com.qrorder.table.dto.UpdateTableRequest
import com.qrorder.table.dto.VerifyPinRequest
import com.qrorder.table.service.DiningTableService
import com.qrorder.table.service.QrCodeService
import com.qrorder.table.service.QrExport
import com.qrorder.table.service.TableAccess
import com.qrorder.table.service.TableSessionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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

private fun staffId(): Long = CurrentPrincipal.requireStaff().userId

private fun guest(): GuestPrincipal = CurrentPrincipal.get() as? GuestPrincipal ?: throw BusinessException(ErrorCode.ACCESS_DENIED)

private fun page(page: Int, size: Int, sort: Sort): PageRequest =
    PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, TableAccess.MAX_PAGE_SIZE), sort)

/** File nhị phân (PNG/PDF): không cache (chứa URL có token), gợi ý tên file. */
private fun binary(export: QrExport, mediaType: MediaType, inline: Boolean): ResponseEntity<ByteArray> {
    val disposition = (if (inline) ContentDisposition.inline() else ContentDisposition.attachment()).filename(export.fileName).build()
    return ResponseEntity.ok()
        .contentType(mediaType)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .body(export.bytes)
}

/** FR-TBL-01..03, FR-QR-01..02, FR-SES-02. Không có xóa (BR-A03): ngừng dùng bằng PATCH /active. */
@Tag(name = "Table - Dining tables", description = "Bàn, sơ đồ bàn, QR của bàn, mở phiên hộ khách")
@RestController
@RequestMapping("/api/v1/tables")
class DiningTableController(
    private val service: DiningTableService,
    private val qrService: QrCodeService,
    private val sessionService: TableSessionService,
) {
    @Operation(summary = "Danh sách bàn (q theo mã/tên, area, status, active; theo khu vực, thứ tự; page từ 0)")
    @GetMapping
    @PreAuthorize(TableAccess.VIEW_TABLES)
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) area: String?,
        @RequestParam(required = false) status: TableStatus?,
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<TableResponse>> =
        ApiResponse.ok(service.search(q, area, status, active, page(page, size, Sort.by("area", "displayOrder", "id"))))

    @Operation(summary = "Sơ đồ bàn: bàn đang dùng kèm phiên hoạt động (FR-TBL-02; cập nhật real-time qua /topic/tables)")
    @GetMapping("/floor-plan")
    @PreAuthorize(TableAccess.VIEW_TABLES)
    fun floorPlan(): ApiResponse<List<FloorPlanTableResponse>> = ApiResponse.ok(service.floorPlan())

    @Operation(summary = "Chi tiết bàn")
    @GetMapping("/{id}")
    @PreAuthorize(TableAccess.VIEW_TABLES)
    fun get(@PathVariable id: Long): ApiResponse<TableResponse> = ApiResponse.ok(service.get(id))

    @Operation(summary = "Tạo bàn (ADMIN)")
    @PostMapping
    @PreAuthorize(TableAccess.ADMIN)
    fun create(@Valid @RequestBody request: CreateTableRequest): ResponseEntity<ApiResponse<TableResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(staffId(), request)))

    @Operation(summary = "Sửa mã, tên, khu vực, sức chứa, thứ tự (ADMIN, gửi kèm version)")
    @PutMapping("/{id}")
    @PreAuthorize(TableAccess.ADMIN)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateTableRequest): ApiResponse<TableResponse> =
        ApiResponse.ok(service.update(staffId(), id, request))

    @Operation(summary = "Ngừng sử dụng / dùng lại bàn (ADMIN; chỉ ngừng khi bàn trống - BR-S08)")
    @PatchMapping("/{id}/active")
    @PreAuthorize(TableAccess.ADMIN)
    fun changeActive(@PathVariable id: Long, @Valid @RequestBody request: ChangeTableActiveRequest): ApiResponse<TableResponse> =
        ApiResponse.ok(service.changeActive(staffId(), id, request))

    @Operation(summary = "Xác nhận dọn xong: CLEANING -> AVAILABLE (STAFF, MANAGER - FR-TBL-03)")
    @PatchMapping("/{id}/status")
    @PreAuthorize(TableAccess.FLOOR)
    fun changeStatus(@PathVariable id: Long, @Valid @RequestBody request: ChangeTableStatusRequest): ApiResponse<TableResponse> =
        ApiResponse.ok(service.changeStatus(staffId(), id, request))

    // ------------------------------------------------------------------------------------------ QR (ADMIN)

    @Operation(summary = "QR đang hoạt động của bàn, kèm URL đầy đủ (ADMIN)")
    @GetMapping("/{id}/qr-code")
    @PreAuthorize(TableAccess.ADMIN)
    fun activeQr(@PathVariable id: Long): ApiResponse<QrCodeResponse> = ApiResponse.ok(qrService.active(id))

    @Operation(summary = "Lịch sử QR của bàn (ADMIN)")
    @GetMapping("/{id}/qr-codes")
    @PreAuthorize(TableAccess.ADMIN)
    fun qrHistory(@PathVariable id: Long): ApiResponse<List<QrCodeResponse>> = ApiResponse.ok(qrService.history(id))

    @Operation(summary = "Sinh QR cho bàn chưa có QR đang hoạt động (ADMIN)")
    @PostMapping("/{id}/qr-codes")
    @PreAuthorize(TableAccess.ADMIN)
    fun generateQr(@PathVariable id: Long): ResponseEntity<ApiResponse<QrCodeResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(qrService.generate(staffId(), id)))

    @Operation(summary = "Xoay QR: mã cũ vô hiệu ngay, tạo mã mới (ADMIN - FR-QR-02)")
    @PostMapping("/{id}/qr-code/rotate")
    @PreAuthorize(TableAccess.ADMIN)
    fun rotateQr(@PathVariable id: Long, @Valid @RequestBody(required = false) request: RotateQrRequest?): ApiResponse<QrCodeResponse> =
        ApiResponse.ok(qrService.rotate(staffId(), id, request ?: RotateQrRequest()))

    @Operation(summary = "Thu hồi QR, lý do bắt buộc (ADMIN - FR-QR-02)")
    @PostMapping("/{id}/qr-code/revoke")
    @PreAuthorize(TableAccess.ADMIN)
    fun revokeQr(@PathVariable id: Long, @Valid @RequestBody request: RevokeQrRequest): ApiResponse<QrCodeResponse> =
        ApiResponse.ok(qrService.revoke(staffId(), id, request))

    @Operation(summary = "Ảnh PNG của QR đang hoạt động (ADMIN; size px trong cấu hình, mặc định 512)")
    @GetMapping("/{id}/qr-code/image.png")
    @PreAuthorize(TableAccess.ADMIN)
    fun qrPng(
        @PathVariable id: Long,
        @RequestParam(required = false) size: Int?,
        @RequestParam(defaultValue = "false") download: Boolean,
    ): ResponseEntity<ByteArray> = binary(qrService.png(staffId(), id, size), MediaType.IMAGE_PNG, inline = !download)

    @Operation(summary = "PDF in QR của một bàn (ADMIN)")
    @GetMapping("/{id}/qr-code/print.pdf")
    @PreAuthorize(TableAccess.ADMIN)
    fun qrPdf(@PathVariable id: Long): ResponseEntity<ByteArray> =
        binary(qrService.pdf(staffId(), listOf(id)), MediaType.APPLICATION_PDF, inline = true)

    // ------------------------------------------------------------------------------------------ Phiên

    @Operation(summary = "Nhân viên mở phiên hộ khách không dùng điện thoại (STAFF, MANAGER - FR-SES-02)")
    @PostMapping("/{id}/sessions")
    @PreAuthorize(TableAccess.FLOOR)
    fun openSession(@PathVariable id: Long): ResponseEntity<ApiResponse<StaffSessionResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(sessionService.openByStaff(staffId(), id)))
}

/** In QR hàng loạt (FR-QR-01). */
@Tag(name = "Table - QR codes", description = "In mã QR hàng loạt")
@RestController
@RequestMapping("/api/v1/qr-codes")
class QrPrintController(private val qrService: QrCodeService) {
    @Operation(summary = "PDF in QR nhiều bàn (ADMIN). Không truyền tableIds = mọi bàn đang dùng có QR đang hoạt động")
    @GetMapping("/print.pdf")
    @PreAuthorize(TableAccess.ADMIN)
    fun printPdf(@RequestParam(required = false) tableIds: List<Long>?): ResponseEntity<ByteArray> =
        binary(qrService.pdf(staffId(), tableIds), MediaType.APPLICATION_PDF, inline = false)
}

/** Phiên bàn cho nhân viên (FR-SES-03, FR-SES-06). */
@Tag(name = "Table - Sessions", description = "Phiên bàn: danh sách, cần xử lý, chi tiết, force-close")
@RestController
@RequestMapping("/api/v1/sessions")
class TableSessionController(private val service: TableSessionService) {
    @Operation(summary = "Danh sách phiên (mặc định OPEN + BILLING; lọc status, tableId, needsAttention; mới mở trước)")
    @GetMapping
    @PreAuthorize(TableAccess.VIEW_SESSIONS)
    fun list(
        @RequestParam(required = false) status: List<SessionStatus>?,
        @RequestParam(required = false) tableId: Long?,
        @RequestParam(required = false) needsAttention: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<StaffSessionResponse>> =
        ApiResponse.ok(
            service.search(status, tableId, needsAttention, page(page, size, Sort.by(Sort.Order.desc("openedAt"), Sort.Order.desc("id")))),
        )

    @Operation(summary = "Phiên cần xử lý: idle quá hạn còn đơn chưa thanh toán (FR-SES-06)")
    @GetMapping("/needs-attention")
    @PreAuthorize(TableAccess.VIEW_SESSIONS)
    fun needsAttention(): ApiResponse<List<StaffSessionResponse>> = ApiResponse.ok(service.needsAttention())

    @Operation(summary = "Chi tiết phiên (gồm PIN bàn nếu bật)")
    @GetMapping("/{id}")
    @PreAuthorize(TableAccess.VIEW_SESSIONS)
    fun detail(@PathVariable id: Long): ApiResponse<StaffSessionResponse> = ApiResponse.ok(service.detail(id))

    @Operation(summary = "Force-close phiên (cần SESSION_FORCE_CLOSE, lý do bắt buộc - BR-S06)")
    @PostMapping("/{id}/force-close")
    @PreAuthorize(TableAccess.FORCE_CLOSE)
    fun forceClose(@PathVariable id: Long, @Valid @RequestBody request: ForceCloseSessionRequest): ApiResponse<StaffSessionResponse> =
        ApiResponse.ok(service.forceClose(staffId(), id, request))
}

/** Khách quét QR (FR-SES-01): không cần đăng nhập; rate limit theo IP (BR-S09). */
@Tag(name = "Public - Sessions", description = "Khách vào phiên bàn bằng mã QR")
@RestController
@RequestMapping("/api/v1/public/sessions")
class PublicSessionController(private val service: TableSessionService) {
    @Operation(summary = "Vào phiên bàn bằng qrToken + deviceId (PIN tùy chọn); trả Guest JWT")
    @PostMapping("/join")
    fun join(@Valid @RequestBody request: JoinSessionRequest): ApiResponse<GuestTokenResponse> =
        ApiResponse.ok(service.join(request, ClientRequestInfo.currentIp()?.hostAddress ?: UNKNOWN_IP))

    companion object {
        const val UNKNOWN_IP = "unknown"
    }
}

/** API của khách đã có Guest JWT (FR-AUTH-04, DEC-07 A). */
@Tag(name = "Guest - Session", description = "Phiên hiện tại của khách, gia hạn Guest JWT, nhập PIN bàn")
@RestController
@RequestMapping("/api/v1/guest/session")
class GuestSessionController(private val service: TableSessionService) {
    @Operation(summary = "Phiên hiện tại (bàn, trạng thái, có được gọi món, cần PIN)")
    @GetMapping
    @PreAuthorize(TableAccess.GUEST)
    fun current(): ApiResponse<GuestSessionResponse> = ApiResponse.ok(service.guestSession(guest()))

    @Operation(summary = "Gia hạn Guest JWT khi phiên còn OPEN/BILLING")
    @PostMapping("/token")
    @PreAuthorize(TableAccess.GUEST)
    fun refreshToken(): ApiResponse<GuestTokenResponse> = ApiResponse.ok(service.refreshGuestToken(guest()))

    @Operation(summary = "Nhập PIN bàn 4 số do nhân viên cấp")
    @PostMapping("/verify-pin")
    @PreAuthorize(TableAccess.GUEST)
    fun verifyPin(@Valid @RequestBody request: VerifyPinRequest): ApiResponse<GuestSessionResponse> =
        ApiResponse.ok(service.verifyPin(guest(), requireNotNull(request.pin)))
}
