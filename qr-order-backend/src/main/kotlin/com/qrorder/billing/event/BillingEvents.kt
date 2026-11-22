package com.qrorder.billing.event

import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.domain.PaymentStatus

/*
 * Domain Event nội bộ của module `billing` (SRS §11.2: in-process). Module `notification` nghe bằng
 * @TransactionalEventListener(AFTER_COMMIT) rồi đẩy STOMP, nên transaction rollback thì không có thông báo nào.
 */

enum class BillChangeKind {
    /** Nhân viên Tính tiền: Bill ACTIVE mới, phiên BILLING (phiếu tạm tính - SRS §6.8). */
    BILL_CREATED,
    /** Phát hành lại Bill để đổi giảm giá (Bill cũ VOIDED, Bill mới thay thế - BE-29). */
    BILL_REISSUED,
    /** Ghi nhận giao dịch PENDING (chờ quẹt thẻ / đối soát chuyển khoản). */
    PAYMENT_PENDING,
    /** Giao dịch SUCCESS (thu thêm một phần). */
    PAYMENT_SUCCEEDED,
    PAYMENT_FAILED,
    /** Thu đủ: Bill PAID, Order PAID. */
    BILL_PAID,
    /** Bill VOIDED: Staff mở lại phiên (BR-P08 a), Void không có tiền thu hoặc hoàn tiền đủ (BR-P09). */
    BILL_VOIDED,
    /** Force-close khi chưa thu đủ (BR-P15). */
    BILL_WRITTEN_OFF,
    /** GĐ5b: Manager yêu cầu Void, có tiền cần hoàn (Bill VOID_PENDING). */
    VOID_REQUESTED,
    /** GĐ5b: Manager rút yêu cầu Void (Bill ACTIVE trở lại). */
    VOID_WITHDRAWN,
    REFUND_PENDING,
    REFUND_SUCCEEDED,
    REFUND_FAILED,
    /** GĐ5b: Manager tạo Bill thay thế sau Void (BR-P16). */
    REPLACEMENT_CREATED,
}

/**
 * Bill/thanh toán thay đổi -> `/topic/session/{sessionId}` (khách + nhân viên) và `/topic/staff/orders`.
 * Chỉ chứa số liệu tiền tổng hợp (không chứa thông tin nhân viên vì kênh phiên có khách nghe).
 */
data class BillChangedEvent(
    val kind: BillChangeKind,
    val sessionId: Long,
    val tableId: Long,
    val tableCode: String,
    val orderId: Long,
    val billId: Long,
    val billNumber: String,
    val billStatus: BillStatus,
    val paymentStatus: PaymentStatus,
    val totalAmount: Long,
    val paidAmount: Long,
    val remainingAmount: Long,
)
