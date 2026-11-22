package com.qrorder.table.session

import com.qrorder.table.domain.SessionStatus

/*
 * Điểm mở rộng của vòng đời phiên bàn (Giai đoạn 3, người dùng chốt). Module Order/Payment (GĐ4-GĐ6) cắm logic vào
 * đây mà KHÔNG sửa luồng lõi của module `table`. Mọi bean được gọi theo thứ tự @Order, trong CÙNG transaction với
 * thao tác đóng phiên và SAU khi đã khóa dòng bàn + dòng phiên (FOR UPDATE).
 */

/** Ngữ cảnh force-close (BR-S06). [reason] đã trim, không rỗng. */
data class SessionForceCloseContext(
    val sessionId: Long,
    val tableId: Long,
    val previousStatus: SessionStatus,
    val actorUserId: Long,
    val reason: String,
)

/**
 * Kiểm tra trước khi force-close; ném BusinessException để từ chối (không đổi gì).
 * Ví dụ GĐ5/GĐ6: Bill VOID_PENDING hoặc có PaymentTransaction PENDING -> từ chối (BR-S06 (i), (v); §9.7 dòng 24).
 */
fun interface SessionForceCloseValidator {
    fun validate(context: SessionForceCloseContext)
}

/**
 * Xử lý dữ liệu phụ thuộc phiên khi force-close được chấp nhận, chạy TRƯỚC khi phiên chuyển FORCE_CLOSED.
 * Ví dụ GĐ4-GĐ6: món/đợt PENDING -> REJECTED (SESSION_FORCE_CLOSED), Order/Bill -> WRITTEN_OFF (ghi thất thu) hoặc
 * VOIDED (BR-S06 (iii)/(iv), BR-P15, BR-P18; DATABASE_DESIGN.md §5.3 (2) về thứ tự cập nhật).
 */
fun interface SessionForceCloseListener {
    fun onForceClose(context: SessionForceCloseContext)
}

/**
 * BR-S07: "đơn chưa thanh toán" là Order OPEN có ít nhất một món không CANCELLED/REJECTED (và, thận trọng, Order
 * LOCKED đang chờ thu tiền). Module `order` cài đặt; Scheduler dùng để chọn EXPIRED hay needs_attention.
 */
fun interface UnpaidOrderChecker {
    fun hasUnpaidOrder(sessionId: Long): Boolean
}

/** Xử lý dữ liệu phụ thuộc khi phiên chuyển EXPIRED (§9.7 dòng 18: Order rỗng -> VOIDED), chạy trước khi đổi phiên. */
fun interface SessionExpiryListener {
    fun onExpire(sessionId: Long)
}
