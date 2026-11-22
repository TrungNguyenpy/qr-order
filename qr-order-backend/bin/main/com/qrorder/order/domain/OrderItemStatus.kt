package com.qrorder.order.domain

/**
 * Trạng thái món (§9.1). Bảng chuyển trạng thái trùng với trigger `fn_order_item_guard`
 * (DB-08: kiểm tra ở cả service lẫn database; đổi một bên phải đổi cả hai bằng migration mới).
 */
enum class OrderItemStatus {
    PENDING, CONFIRMED, PREPARING, READY, SERVED, CANCELLED, REJECTED;

    /** SERVED, CANCELLED, REJECTED. */
    val isFinal: Boolean get() = this == SERVED || this == CANCELLED || this == REJECTED

    /** CANCELLED/REJECTED: bắt buộc reasonCode (BR-O08), có closed_at. */
    val isClosedWithoutServing: Boolean get() = this == CANCELLED || this == REJECTED

    /** Món tính tiền (BR-P18): CONFIRMED, PREPARING, READY, SERVED. */
    val isBillable: Boolean get() = this == CONFIRMED || this == PREPARING || this == READY || this == SERVED

    /** Món đã bắt đầu chế biến: hủy/từ chối ở trạng thái này luôn wasted (BR-O06). */
    val isCooking: Boolean get() = this == PREPARING || this == READY

    fun canTransitionTo(target: OrderItemStatus): Boolean = target in (TRANSITIONS[this] ?: emptySet())

    companion object {
        val TRANSITIONS: Map<OrderItemStatus, Set<OrderItemStatus>> = mapOf(
            PENDING to setOf(CONFIRMED, REJECTED, CANCELLED),
            CONFIRMED to setOf(PREPARING, CANCELLED, REJECTED),
            PREPARING to setOf(READY, REJECTED, CANCELLED),
            READY to setOf(SERVED, CANCELLED),
            SERVED to emptySet(),
            CANCELLED to emptySet(),
            REJECTED to emptySet(),
        )
    }
}

/**
 * Người thực hiện một lần đổi trạng thái món. Bắt buộc truyền ở mọi lần đổi trạng thái để trigger
 * `trg_order_item_status_history` ghi đúng người vào order_item_status_history (BR-O09, DB-14).
 */
sealed interface StatusActor {
    /** Nhân viên (Staff, Kitchen, Manager): ghi status_changed_by = userId. */
    data class Staff(val userId: Long) : StatusActor

    /** Khách: ghi thiết bị thực hiện (có thể khác thiết bị đã đặt món). */
    data class Guest(val deviceId: String) : StatusActor

    /** Hệ thống: ví dụ tự REJECTED khi món SOLD_OUT (BR-O13) hoặc force-close. */
    data object System : StatusActor
}
