package com.qrorder.order.service

import com.qrorder.order.domain.Order

/**
 * Điểm mở rộng sau mỗi lần món đổi trạng thái (GĐ5a). Module `billing` cài đặt để tự đóng phiên khi Bill đã PAID và
 * món tính tiền cuối cùng vừa xong (SERVED, hoặc bị từ chối/hủy) - quy tắc đóng phiên đã chốt cho GĐ5a.
 *
 * Gọi trong CÙNG transaction với thay đổi món, SAU khi bàn + phiên đã khóa FOR UPDATE và món đã flush, nên bên cài đặt
 * đọc được trạng thái mới nhất của mọi món trong Order. [order] là entity đã nạp lại sau khi khóa.
 * [actorUserId] là nhân viên thực hiện (null khi khách hoặc hệ thống).
 */
fun interface OrderItemProgressListener {
    fun afterItemStatusChanged(locked: LockedSession, order: Order, actorUserId: Long?)
}
