package com.qrorder.table.event

import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.domain.TableStatus

/*
 * Domain Event nội bộ của module `table` (SRS §11.2, §11.3: in-process). Module `notification` lắng nghe bằng
 * @TransactionalEventListener(AFTER_COMMIT) rồi đẩy STOMP, nên transaction rollback thì không có thông báo nào.
 */

enum class TableChangeKind { CREATED, UPDATED, ACTIVE_CHANGED, STATUS_CHANGED }

/** Bàn thay đổi -> `/topic/tables` (FR-TBL-02: sơ đồ bàn real-time). [sessionId] là phiên đang hoạt động (nếu có). */
data class TableChangedEvent(
    val tableId: Long,
    val code: String,
    val status: TableStatus,
    val active: Boolean,
    val version: Long,
    val change: TableChangeKind,
    val sessionId: Long? = null,
)

enum class SessionChangeKind {
    /** Phiên mới được mở (quét QR hoặc nhân viên mở). */
    OPENED,
    /** Phiên kết thúc (EXPIRED / FORCE_CLOSED; CLOSED từ GĐ5a khi Bill PAID và món đã xong): client khách dừng thao tác và ngắt kết nối. */
    CLOSED,
    /** Phiên idle quá hạn còn đơn chưa thanh toán (BR-S07, FR-SES-06). */
    NEEDS_ATTENTION,
    /** GĐ5a (BR-P05): nhân viên Tính tiền, phiên OPEN -> BILLING (khách không gửi đợt mới, xem phiếu tạm tính). */
    BILLING_STARTED,
    /** GĐ5a (BR-P08 a): Staff mở lại phiên BILLING -> OPEN (Bill cũ VOIDED, khách gọi thêm/chỉnh món được). */
    REOPENED,
}

/**
 * Phiên bàn thay đổi -> `/topic/session/{sessionId}` (khách + nhân viên) và, với [SessionChangeKind.NEEDS_ATTENTION],
 * `/topic/staff/orders` (SRS §11.4). Không chứa thông tin nhân viên (kênh phiên có khách nghe).
 */
data class SessionChangedEvent(
    val sessionId: Long,
    val tableId: Long,
    val tableCode: String,
    val status: SessionStatus,
    val version: Long,
    val change: SessionChangeKind,
)
