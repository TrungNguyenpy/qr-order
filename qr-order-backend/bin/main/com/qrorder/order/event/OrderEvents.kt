package com.qrorder.order.event

import com.qrorder.order.domain.OrderItemStatus

/*
 * Domain Event nội bộ của module `order` (SRS §11.2: in-process). Module `notification` nghe bằng
 * @TransactionalEventListener(AFTER_COMMIT) rồi đẩy STOMP nên transaction rollback thì không có thông báo nào.
 */

enum class OrderChangeKind {
    /** Khách gửi đợt mới, hoặc nhân viên đặt món hộ khách (FR-ORD-05). */
    BATCH_SUBMITTED,
    /** Nhân viên nhận xử lý đợt (claim) hoặc nhả claim. */
    BATCH_CLAIM_CHANGED,
    /** Đợt được xác nhận / từ chối (một phần hoặc cả đợt). */
    BATCH_DECIDED,
    /** Bếp/Staff đổi trạng thái món (PREPARING, READY, SERVED, từ chối). */
    ITEM_STATUS_CHANGED,
    /** Khách hoặc nhân viên hủy món PENDING/CONFIRMED. */
    ITEM_CANCELLED,
    /** Nhân viên đổi số lượng món PENDING/CONFIRMED (FR-ORD-05). */
    ITEM_QUANTITY_CHANGED,
    /** Khách gửi yêu cầu hủy món đang nấu (chờ Manager). */
    CANCEL_REQUESTED,
    /** Manager duyệt/từ chối yêu cầu hủy. */
    CANCEL_DECIDED,
    /** Hệ thống từ chối món: SOLD_OUT (BR-O13) hoặc force-close. */
    SYSTEM_REJECTED,
    /** Order đóng do force-close/hết hạn (VOIDED/WRITTEN_OFF). */
    ORDER_CLOSED,
}

/** Ảnh chụp tối thiểu của một món trong sự kiện (không chứa thông tin nhân viên - kênh phiên có khách nghe). */
data class OrderItemSnapshot(
    val itemId: Long,
    val productName: String,
    val quantity: Int,
    val status: OrderItemStatus,
    val reasonCode: String? = null,
)

/**
 * Order/đợt/món thay đổi. [kitchenRelevant]: có món đi vào/ra danh sách bếp (CONFIRMED/PREPARING) -> `/topic/kitchen/orders`.
 * [staffRelevant]: Staff/Manager cần biết -> `/topic/staff/orders`. [readyRelevant]: có món READY -> `/topic/staff/ready`.
 * Mọi sự kiện đều gửi tới `/topic/session/{sessionId}`.
 */
data class OrderChangedEvent(
    val kind: OrderChangeKind,
    val sessionId: Long,
    val tableId: Long,
    val tableCode: String,
    val orderId: Long,
    val batchId: Long?,
    val items: List<OrderItemSnapshot>,
    val kitchenRelevant: Boolean = false,
    val staffRelevant: Boolean = false,
    val readyRelevant: Boolean = false,
)
