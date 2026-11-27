package com.qrorder.billing.dto

import com.qrorder.billing.service.BankTransferInfo
import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.domain.DiscountType
import com.qrorder.billing.domain.PaymentMethod
import com.qrorder.billing.domain.PaymentStatus
import com.qrorder.billing.domain.TransactionStatus
import com.qrorder.billing.domain.VoidType
import com.qrorder.order.domain.OrderItemStatus
import com.qrorder.order.domain.OrderStatus
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.domain.TableStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

private const val REASON_MAX = 1000
private const val NOTE_MAX = 500

// =============================================================================================== Request

/**
 * Giảm giá (BR-P07): PERCENT (value 0 < x <= 100, tối đa 2 chữ số thập phân) hoặc FIXED_AMOUNT (value là số VND
 * nguyên, 0 < x <= Subtotal); NONE = không giảm (chỉ có ý nghĩa khi phát hành lại Bill). Lý do bắt buộc.
 */
data class DiscountRequest(
    @field:NotNull(message = "Hình thức giảm giá là bắt buộc")
    val type: DiscountType?,
    val value: BigDecimal? = null,
    @field:NotBlank(message = "Lý do giảm giá là bắt buộc")
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 1000 ký tự")
    val reason: String?,
)

/** POST /api/v1/orders/{orderId}/bill. [discount] tùy chọn, cần thêm quyền BILL_DISCOUNT (BR-P07). */
data class CreateBillRequest(
    @field:Valid
    val discount: DiscountRequest? = null,
)

/** PUT /api/v1/bills/{billId}/discount: phát hành lại Bill với giảm giá mới (BE-29). [version] của Bill hiện tại (tùy chọn). */
data class UpdateBillDiscountRequest(
    @field:NotNull(message = "Hình thức giảm giá là bắt buộc")
    val type: DiscountType?,
    val value: BigDecimal? = null,
    @field:NotBlank(message = "Lý do giảm giá là bắt buộc")
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 1000 ký tự")
    val reason: String?,
    val version: Long? = null,
)

/** Trạng thái khi ghi nhận: SUCCESS (đã thu ngay) hoặc PENDING (chờ quẹt thẻ / đối soát - DEC-11 C). */
enum class PaymentEntryStatus { PENDING, SUCCESS }

/** POST /api/v1/bills/{billId}/payments (BR-P10). Idempotent theo [requestId] (client sinh UUID cho mỗi lần thu). */
data class RecordPaymentRequest(
    @field:NotNull(message = "requestId là bắt buộc")
    val requestId: UUID?,
    @field:NotNull(message = "Phương thức thanh toán là bắt buộc")
    val method: PaymentMethod?,
    @field:NotNull(message = "Số tiền là bắt buộc")
    @field:Positive(message = "Số tiền phải lớn hơn 0")
    val amount: Long?,
    @field:NotNull(message = "Trạng thái giao dịch là bắt buộc (PENDING hoặc SUCCESS)")
    val status: PaymentEntryStatus?,
    @field:Size(max = 50, message = "provider tối đa 50 ký tự")
    val provider: String? = null,
    @field:Size(max = 100, message = "providerTxnId tối đa 100 ký tự")
    val providerTxnId: String? = null,
    @field:Size(max = NOTE_MAX, message = "Ghi chú tối đa 500 ký tự")
    val note: String? = null,
)

enum class PaymentConfirmResult { SUCCESS, FAILED }

/** POST /api/v1/payments/{paymentId}/confirm: PENDING -> SUCCESS hoặc FAILED (failureReason bắt buộc khi FAILED). */
data class ConfirmPaymentRequest(
    @field:NotNull(message = "Kết quả là bắt buộc (SUCCESS hoặc FAILED)")
    val result: PaymentConfirmResult?,
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 1000 ký tự")
    val failureReason: String? = null,
    @field:Size(max = 50, message = "provider tối đa 50 ký tự")
    val provider: String? = null,
    @field:Size(max = 100, message = "providerTxnId tối đa 100 ký tự")
    val providerTxnId: String? = null,
)

/** POST /api/v1/sessions/{sessionId}/reopen (BR-P08 a, FR-PAY-06). [version] của phiên (tùy chọn). */
data class ReopenSessionRequest(
    @field:NotBlank(message = "Lý do mở lại phiên là bắt buộc")
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 1000 ký tự")
    val reason: String?,
    val version: Long? = null,
)

// =============================================================================================== Response

/** Một dòng món tính tiền trên Bill (gộp tên option để in). [unitPrice] = đơn giá + phụ thu option. */
data class BillLineResponse(
    val itemId: Long,
    val productName: String,
    val options: List<String>,
    val note: String?,
    val quantity: Int,
    val unitPrice: Long,
    val lineTotal: Long,
    val status: OrderItemStatus,
)

data class PaymentResponse(
    val id: Long,
    val billId: Long,
    val requestId: UUID,
    val receiptNumber: String?,
    val method: PaymentMethod,
    val amount: Long,
    val status: TransactionStatus,
    val provider: String?,
    val providerTxnId: String?,
    val note: String?,
    val failureReason: String?,
    val createdBy: Long,
    val createdAt: OffsetDateTime?,
    val confirmedBy: Long?,
    val confirmedAt: OffsetDateTime?,
)

/**
 * Bill cho nhân viên. Số liệu tiền là snapshot (BR-P06). [paidAmount] = tiền thu ròng (TPS - TRS), [pendingAmount] = TPP,
 * [remainingAmount] = số còn có thể ghi nhận = Total - (TPS + TPP - TRS) (BR-P10). [lines] là món tính tiền hiện tại của
 * Order (rỗng với Bill VOIDED vì Order có thể đã đổi sau đó).
 */
data class BillResponse(
    val id: Long,
    val billNumber: String,
    val orderId: Long,
    val orderStatus: OrderStatus,
    val sessionId: Long,
    val sessionStatus: SessionStatus,
    val tableId: Long,
    val tableCode: String,
    val status: BillStatus,
    val paymentStatus: PaymentStatus,
    val subtotalAmount: Long,
    val discountAmount: Long,
    val discountReason: String?,
    val discountedBy: Long?,
    val netAmount: Long,
    val serviceFeeRate: BigDecimal,
    val serviceFeeTaxable: Boolean?,
    val serviceFeeAmount: Long,
    val totalAmount: Long,
    val vatRate: BigDecimal,
    val vatAmount: Long,
    val paidAmount: Long,
    val pendingAmount: Long,
    val remainingAmount: Long,
    val replacesBillId: Long?,
    val createdBy: Long,
    val createdAt: OffsetDateTime?,
    val paidAt: OffsetDateTime?,
    val voidType: VoidType?,
    val voidReason: String?,
    val voidedAt: OffsetDateTime?,
    val writtenOffAt: OffsetDateTime?,
    val lines: List<BillLineResponse>,
    val payments: List<PaymentResponse>,
    val version: Long,
    /** GĐ5b (BR-P13): TRS, TRP và remaining_refundable = TPS - TRS - TRP. */
    val refundedAmount: Long = 0,
    val refundPendingAmount: Long = 0,
    val remainingRefundable: Long = 0,
    val voidRequestedAt: OffsetDateTime? = null,
    /** BR-P17: Order LOCKED chờ Bill thay thế. */
    val orderNeedsAttention: Boolean = false,
    val refunds: List<RefundResponse> = emptyList(),
    /** VietQR: có khi Bill ACTIVE còn tiền phải thu và nhà hàng đã cấu hình ngân hàng; số tiền = [remainingAmount]. */
    val bankTransfer: BankTransferInfo? = null,
)

/** Phiếu tạm tính cho khách (UC10): không có thông tin nhân viên, không có chi tiết giao dịch. */
data class GuestBillResponse(
    val billNumber: String,
    val status: BillStatus,
    val paymentStatus: PaymentStatus,
    val subtotalAmount: Long,
    val discountAmount: Long,
    val netAmount: Long,
    val serviceFeeAmount: Long,
    val totalAmount: Long,
    val vatRate: BigDecimal,
    val vatAmount: Long,
    val paidAmount: Long,
    val remainingAmount: Long,
    val lines: List<BillLineResponse>,
    val createdAt: OffsetDateTime?,
    /** VietQR: có khi Bill ACTIVE còn tiền phải thu và nhà hàng đã cấu hình ngân hàng. */
    val bankTransfer: BankTransferInfo? = null,
)

/** Tóm tắt Bill sau một thao tác thanh toán. */
data class BillSummaryResponse(
    val id: Long,
    val billNumber: String,
    val status: BillStatus,
    val paymentStatus: PaymentStatus,
    val totalAmount: Long,
    val paidAmount: Long,
    val pendingAmount: Long,
    val remainingAmount: Long,
    val version: Long,
)

/**
 * Kết quả ghi nhận/xác nhận thanh toán. [replayed] = true khi gửi lại cùng requestId (hoặc xác nhận lại cùng kết quả):
 * không ghi thêm gì. [sessionClosed] = true khi giao dịch này làm phiên đóng (Bill PAID và món đã xong).
 */
data class PaymentResultResponse(
    val payment: PaymentResponse,
    val bill: BillSummaryResponse,
    val orderStatus: OrderStatus,
    val sessionStatus: SessionStatus,
    val sessionClosed: Boolean,
    val replayed: Boolean,
)

data class TransactionHistoryResponse(
    val fromStatus: TransactionStatus?,
    val toStatus: TransactionStatus,
    val changedBy: Long?,
    val changedAt: OffsetDateTime?,
    val reason: String?,
)

data class PaymentDetailResponse(
    val payment: PaymentResponse,
    val history: List<TransactionHistoryResponse>,
)

/** Thông tin nhà hàng in trên phiếu (app.receipt.*). */
data class ReceiptHeader(
    val restaurantName: String,
    val address: String?,
    val phone: String?,
    val taxCode: String?,
)

/**
 * Dữ liệu in phiếu thanh toán nội bộ (FR-PAY-12, BR-P12) cho một giao dịch SUCCESS. Không phải hóa đơn điện tử
 * (DEC-11 C). [paidBefore] / [paidAfter] = tổng các giao dịch SUCCESS của Bill trước / tính cả giao dịch này
 * (theo thứ tự xác nhận); [remainingAfter] = Total - paidAfter (không âm).
 */
data class PaymentReceiptResponse(
    val header: ReceiptHeader,
    val receiptNumber: String,
    val issuedAt: OffsetDateTime?,
    val billNumber: String,
    val billStatus: BillStatus,
    val tableCode: String,
    val tableName: String?,
    val sessionOpenedAt: OffsetDateTime?,
    val cashierName: String?,
    val method: PaymentMethod,
    val amount: Long,
    val provider: String?,
    val providerTxnId: String?,
    val lines: List<BillLineResponse>,
    val subtotalAmount: Long,
    val discountAmount: Long,
    val discountReason: String?,
    val netAmount: Long,
    val serviceFeeRate: BigDecimal,
    val serviceFeeAmount: Long,
    val totalAmount: Long,
    val vatRate: BigDecimal,
    val vatAmount: Long,
    val paidBefore: Long,
    val paidAfter: Long,
    val remainingAfter: Long,
    val footer: String,
)

// =============================================================================================== GĐ5b: Void, Refund, Bill thay thế

/** POST /api/v1/bills/{billId}/void (BR-P09): lý do bắt buộc. [version] của Bill (tùy chọn). */
data class RequestVoidRequest(
    @field:NotBlank(message = "Lý do Void là bắt buộc")
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 1000 ký tự")
    val reason: String?,
    val version: Long? = null,
)

/** POST /api/v1/bills/{billId}/void/withdraw (BR-P14): lý do bắt buộc. */
data class WithdrawVoidRequest(
    @field:NotBlank(message = "Lý do rút Void là bắt buộc")
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 1000 ký tự")
    val reason: String?,
)

/** Kết quả yêu cầu Void: Bill sau thao tác + số tiền cần hoàn (remaining_refundable). */
data class VoidResultResponse(
    val bill: BillResponse,
    val voidCompleted: Boolean,
)

/**
 * POST /api/v1/bills/{billId}/refunds (BR-P13): idempotent theo [requestId]. SUCCESS chỉ khi tiền thực tế đã hoàn
 * cho khách; PENDING khi đang chờ (ví dụ chuyển khoản trả lại).
 */
data class RecordRefundRequest(
    @field:NotNull(message = "requestId là bắt buộc")
    val requestId: UUID?,
    @field:NotNull(message = "Phương thức hoàn tiền là bắt buộc")
    val method: PaymentMethod?,
    @field:NotNull(message = "Số tiền là bắt buộc")
    @field:Positive(message = "Số tiền phải lớn hơn 0")
    val amount: Long?,
    @field:NotNull(message = "Trạng thái giao dịch là bắt buộc (PENDING hoặc SUCCESS)")
    val status: PaymentEntryStatus?,
    @field:NotBlank(message = "Lý do hoàn tiền là bắt buộc")
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 1000 ký tự")
    val reason: String?,
)

/** POST /api/v1/refunds/{refundId}/confirm: PENDING -> SUCCESS hoặc FAILED (failureReason bắt buộc khi FAILED). */
data class ConfirmRefundRequest(
    @field:NotNull(message = "Kết quả là bắt buộc (SUCCESS hoặc FAILED)")
    val result: PaymentConfirmResult?,
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 1000 ký tự")
    val failureReason: String? = null,
)

data class RefundResponse(
    val id: Long,
    val billId: Long,
    val requestId: UUID,
    val receiptNumber: String?,
    val method: PaymentMethod,
    val amount: Long,
    val status: TransactionStatus,
    val reason: String,
    val failureReason: String?,
    val createdBy: Long,
    val createdAt: OffsetDateTime?,
    val confirmedBy: Long?,
    val confirmedAt: OffsetDateTime?,
)

/** Tóm tắt tiền hoàn của Bill sau một thao tác Refund (BR-P13). */
data class RefundSummaryResponse(
    val billId: Long,
    val billNumber: String,
    val billStatus: BillStatus,
    val paymentStatus: PaymentStatus,
    val totalPaidSuccess: Long,
    val refundedAmount: Long,
    val refundPendingAmount: Long,
    val remainingRefundable: Long,
    val netCollected: Long,
)

/** Kết quả ghi nhận/xác nhận Refund. [voidCompleted] = lần này hoàn đủ, Bill vừa chuyển VOIDED. */
data class RefundResultResponse(
    val refund: RefundResponse,
    val summary: RefundSummaryResponse,
    val orderStatus: OrderStatus,
    val orderNeedsAttention: Boolean,
    val voidCompleted: Boolean,
    val replayed: Boolean,
)

data class RefundDetailResponse(
    val refund: RefundResponse,
    val history: List<TransactionHistoryResponse>,
)

/** Dữ liệu in phiếu hoàn tiền nội bộ RF-YYYYMMDD-NNNN (FR-PAY-12) cho Refund SUCCESS. */
data class RefundReceiptResponse(
    val header: ReceiptHeader,
    val receiptNumber: String,
    val issuedAt: OffsetDateTime?,
    val billNumber: String,
    val billStatus: BillStatus,
    val tableCode: String,
    val tableName: String?,
    val managerName: String?,
    val method: PaymentMethod,
    val amount: Long,
    val reason: String,
    val voidReason: String?,
    val billTotalAmount: Long,
    val totalPaidSuccess: Long,
    val refundedBefore: Long,
    val refundedAfter: Long,
    val remainingToRefund: Long,
    val footer: String,
)

/** [BILL_AWAITING_PAYMENT]: Bill hiệu lực chưa thu đủ của phiên đã đóng (ví dụ Bill thay thế), cần thu tiền. */
enum class AttentionType { BILL_VOID_PENDING, AWAITING_REPLACEMENT_BILL, BILL_AWAITING_PAYMENT }

/**
 * Một mục "Cần xử lý" (FR-PAY-13, BR-P17): Bill đang chờ hoàn tiền hoặc Order LOCKED chờ Bill thay thế. Staff chỉ xem
 * (gửi MANAGER_SUPPORT), Manager xử lý.
 */
data class BillingAttentionItem(
    val type: AttentionType,
    val orderId: Long,
    val sessionId: Long,
    val sessionStatus: SessionStatus?,
    val tableCode: String?,
    val billId: Long?,
    val billNumber: String?,
    val since: OffsetDateTime?,
    val remainingRefundable: Long?,
    val reason: String?,
    /** Chỉ có với BILL_AWAITING_PAYMENT: số còn phải thu. */
    val remainingAmount: Long? = null,
)

data class ReopenSessionResponse(
    val sessionId: Long,
    val sessionStatus: SessionStatus,
    val tableId: Long,
    val tableStatus: TableStatus,
    val orderId: Long,
    val orderStatus: OrderStatus,
    val voidedBillId: Long,
    val voidedBillNumber: String,
    val sessionVersion: Long,
)
