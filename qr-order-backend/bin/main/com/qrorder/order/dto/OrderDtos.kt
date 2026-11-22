package com.qrorder.order.dto

import com.qrorder.order.domain.BatchStatus
import com.qrorder.order.domain.CancelRequestStatus
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.OrderStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

/*
 * Hợp đồng API Giai đoạn 4 - Order & Kitchen (Customer Web, Staff App).
 * - Tiền là số nguyên VND (BIGINT), thời điểm ISO-8601, id là số.
 * - Phản hồi dành cho KHÁCH không có thông tin nhân viên (id, tên); phản hồi nhân viên có claimedBy/decidedBy.
 * - reasonCode: UPPER_SNAKE_CASE 2-50 ký tự (CHECK ck_order_item_reason_code, BR-O08).
 */

private const val REASON_CODE_PATTERN = "^[A-Z][A-Z0-9_]{1,49}$"
private const val REASON_CODE_MESSAGE = "Mã lý do phải dạng UPPER_SNAKE_CASE (2-50 ký tự)"

// =============================================================================================== Yêu cầu

/** Một dòng món trong đợt order. [optionItemIds]: các lựa chọn option đã chọn (mọi nhóm, không trùng). */
data class PlaceOrderLine(
    @field:NotNull(message = "Thiếu productId")
    val productId: Long?,

    @field:NotNull(message = "Thiếu số lượng")
    val quantity: Int?,

    @field:Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    val note: String? = null,

    @field:Size(max = 50, message = "Tối đa 50 lựa chọn option mỗi món")
    val optionItemIds: List<Long> = emptyList(),
)

/** POST /api/v1/guest/orders. [clientRequestId] (UUID do client sinh) làm idempotency key (BR-O04). */
data class PlaceOrderRequest(
    @field:NotNull(message = "Thiếu clientRequestId")
    val clientRequestId: UUID?,

    @field:NotEmpty(message = "Đợt order phải có ít nhất một món")
    @field:Size(max = 50, message = "Mỗi đợt tối đa 50 dòng món")
    @field:Valid
    val items: List<PlaceOrderLine>?,
)

/** Khách hủy món PENDING/CONFIRMED (reasonCode cố định GUEST_CANCELLED, ghi chú tùy chọn). */
data class GuestCancelItemRequest(
    @field:Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    val note: String? = null,
)

/** Khách gửi yêu cầu hủy món PREPARING/READY (BR-O06). */
data class CreateCancelRequestRequest(
    @field:Size(max = 1000, message = "Lý do tối đa 1000 ký tự")
    val reason: String? = null,
)

/** Từ chối một dòng món khi xác nhận đợt (BR-O08: reasonCode bắt buộc). */
data class RejectItemRequest(
    @field:NotNull(message = "Thiếu itemId")
    val itemId: Long?,

    @field:NotBlank(message = "Thiếu reasonCode")
    @field:Pattern(regexp = REASON_CODE_PATTERN, message = REASON_CODE_MESSAGE)
    val reasonCode: String?,

    @field:Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự")
    val reasonNote: String? = null,
)

/** Xác nhận đợt: mọi món còn lại thành CONFIRMED; [rejectItems] (tùy chọn) từ chối một phần -> PARTIALLY_ACCEPTED. */
data class ConfirmBatchRequest(
    @field:Valid
    val rejectItems: List<RejectItemRequest> = emptyList(),
)

/** Từ chối cả đợt -> mọi món REJECTED, đợt REJECTED. */
data class RejectBatchRequest(
    @field:NotBlank(message = "Thiếu reasonCode")
    @field:Pattern(regexp = REASON_CODE_PATTERN, message = REASON_CODE_MESSAGE)
    val reasonCode: String?,

    @field:Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự")
    val reasonNote: String? = null,
)

/** Bếp/Staff đổi trạng thái món (PREPARING hoặc READY cho bếp; SERVED cho Staff qua endpoint riêng). */
data class ChangeItemStatusRequest(
    @field:NotNull(message = "Thiếu trạng thái đích")
    val status: OrderItemStatus?,
)

/** Bếp từ chối món CONFIRMED/PREPARING (ví dụ OUT_OF_STOCK). */
data class KitchenRejectItemRequest(
    @field:NotBlank(message = "Thiếu reasonCode")
    @field:Pattern(regexp = REASON_CODE_PATTERN, message = REASON_CODE_MESSAGE)
    val reasonCode: String?,

    @field:Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự")
    val reasonNote: String? = null,
)

/** Manager duyệt yêu cầu hủy (ghi chú tùy chọn). */
data class ApproveCancelRequestRequest(
    @field:Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự")
    val note: String? = null,
)

/** Manager từ chối yêu cầu hủy: lý do bắt buộc (ck_item_cancel_request_deny_reason). */
data class DenyCancelRequestRequest(
    @field:NotBlank(message = "Lý do từ chối là bắt buộc")
    @field:Size(max = 1000, message = "Lý do tối đa 1000 ký tự")
    val reason: String?,
)

// =============================================================================================== Phản hồi

data class OrderOptionResponse(
    val groupName: String,
    val optionName: String,
    val extraPrice: Long,
)

data class CancelRequestSummary(
    val id: Long,
    val status: CancelRequestStatus,
    val requestedAt: OffsetDateTime?,
)

data class OrderItemResponse(
    val id: Long,
    val batchId: Long,
    val productId: Long,
    val productName: String,
    val unitPrice: Long,
    val optionExtraPerUnit: Long,
    val quantity: Int,
    val lineTotal: Long,
    val note: String?,
    val status: OrderItemStatus,
    val reasonCode: String?,
    val wasted: Boolean,
    val options: List<OrderOptionResponse>,
    /** Yêu cầu hủy gần nhất của món (nếu có). */
    val cancelRequest: CancelRequestSummary?,
    val createdAt: OffsetDateTime?,
    val confirmedAt: OffsetDateTime?,
    val preparingAt: OffsetDateTime?,
    val readyAt: OffsetDateTime?,
    val servedAt: OffsetDateTime?,
    val closedAt: OffsetDateTime?,
    val version: Long,
)

data class OrderBatchResponse(
    val id: Long,
    val seq: Int,
    val status: BatchStatus,
    val requiresConfirmation: Boolean,
    val submittedAt: OffsetDateTime?,
    val decidedAt: OffsetDateTime?,
    val reasonCode: String?,
    val items: List<OrderItemResponse>,
    val version: Long,
)

/** Order của phiên cho khách. [billableTotal] = tổng thành tiền các món tính tiền (BR-P18), chưa gồm phí phục vụ. */
data class GuestOrderResponse(
    val orderId: Long?,
    val sessionId: Long,
    val status: OrderStatus?,
    val batches: List<OrderBatchResponse>,
    val billableTotal: Long,
    /** true khi yêu cầu gửi lại cùng clientRequestId và trả kết quả của lần gửi trước (idempotent). */
    val replayed: Boolean = false,
    /** Đợt vừa gửi (chỉ có ở phản hồi của POST). */
    val batchId: Long? = null,
)

/** Đợt chờ xác nhận / đã xử lý cho nhân viên: kèm bàn và người giữ claim. */
data class StaffBatchResponse(
    val id: Long,
    val orderId: Long,
    val sessionId: Long,
    val tableId: Long,
    val tableCode: String,
    val seq: Int,
    val status: BatchStatus,
    val requiresConfirmation: Boolean,
    val submittedAt: OffsetDateTime?,
    val claimedBy: Long?,
    val claimedAt: OffsetDateTime?,
    /** Claim còn hiệu lực (chưa quá batch_claim_ttl_seconds) theo đồng hồ DB. */
    val claimActive: Boolean,
    val claimExpiresAt: OffsetDateTime?,
    val decidedBy: Long?,
    val decidedAt: OffsetDateTime?,
    val reasonCode: String?,
    val items: List<OrderItemResponse>,
    val version: Long,
)

/** Order của một phiên cho nhân viên. */
data class StaffOrderResponse(
    val orderId: Long?,
    val sessionId: Long,
    val tableCode: String,
    val status: OrderStatus?,
    val batches: List<StaffBatchResponse>,
    val billableTotal: Long,
)

/** Một dòng trên màn hình bếp (KDS, FR-KIT-01): món CONFIRMED/PREPARING, cũ trước. */
data class KitchenItemResponse(
    val itemId: Long,
    val orderId: Long,
    val batchId: Long,
    val sessionId: Long,
    val tableCode: String,
    val productName: String,
    val quantity: Int,
    val note: String?,
    val options: List<OrderOptionResponse>,
    val status: OrderItemStatus,
    val confirmedAt: OffsetDateTime?,
    val preparingAt: OffsetDateTime?,
    val readyAt: OffsetDateTime?,
    /** Số giây chờ tính từ lúc xác nhận (theo đồng hồ DB) để bếp ưu tiên món chờ lâu. */
    val waitingSeconds: Long,
    val version: Long,
)

data class CancelRequestResponse(
    val id: Long,
    val orderItemId: Long,
    val orderId: Long,
    val sessionId: Long,
    val tableCode: String,
    val productName: String,
    val quantity: Int,
    val itemStatusAtRequest: OrderItemStatus,
    val currentItemStatus: OrderItemStatus,
    val status: CancelRequestStatus,
    val requestReason: String?,
    val requestedAt: OffsetDateTime?,
    val decidedBy: Long?,
    val decidedAt: OffsetDateTime?,
    val decisionReason: String?,
    val version: Long,
)

/** Kết quả thao tác trên một món (kitchen/staff/manager). */
data class ItemActionResponse(
    val item: OrderItemResponse,
    val batchStatus: BatchStatus,
)
