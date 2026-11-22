package com.qrorder.order.domain

import com.qrorder.common.domain.StatusActorType
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.Generated
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.generator.EventType
import java.time.OffsetDateTime

/**
 * Dòng món. Tên, đơn giá, phụ thu option là snapshot lúc đặt (BR-O01) - các cột này updatable = false
 * và trigger `fn_order_item_guard` cấm sửa.
 *
 * QUY ƯỚC BẮT BUỘC (DATABASE_DESIGN.md §5.3 (12), DB-14): trạng thái món CHỈ được đổi qua
 * [changeStatus], hàm này luôn ghi đủ 3 cột người thực hiện (status_changed_by_type,
 * status_changed_by, status_changed_by_device_id) để trigger ghi lịch sử đúng người.
 * Setter của [status] và các cột người thực hiện vì vậy là protected.
 *
 * Kiểm tra BR-O12 (Order phải OPEN khi hủy/từ chối/sửa món) cần trạng thái Order nên thuộc service
 * Order; trigger là hàng rào cuối (ORDER_NOT_OPEN).
 */
@Entity
@Table(name = "order_item")
class OrderItem(
    @Column(name = "order_id", nullable = false, updatable = false)
    var orderId: Long,

    @Column(name = "batch_id", nullable = false, updatable = false)
    var batchId: Long,

    @Column(name = "product_id", nullable = false, updatable = false)
    var productId: Long,

    @Column(name = "product_name", nullable = false, length = 150, updatable = false)
    var productName: String,

    @Column(name = "unit_price", nullable = false, updatable = false)
    var unitPrice: Long,

    @Column(name = "option_extra_per_unit", nullable = false, updatable = false)
    var optionExtraPerUnit: Long,

    quantity: Int,

    /** Thiết bị khách đặt món (null khi Staff order hộ). */
    @Column(name = "device_id", length = 64, updatable = false)
    var deviceId: String? = null,

    /** Staff order hộ (null khi khách đặt). */
    @Column(name = "created_by_user_id", updatable = false)
    var createdByUserId: Long? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    /**
     * BE-04: số lượng chỉ đổi qua [changeQuantity] để [lineTotal] luôn khớp (CHECK ck_order_item_line_total).
     * Trigger chỉ cho sửa khi món PENDING/CONFIRMED và Order OPEN.
     */
    @Column(name = "quantity", nullable = false)
    var quantity: Int = quantity
        protected set

    /** BR-P02: (đơn giá + phụ thu option) x số lượng; CHECK ck_order_item_line_total. */
    @Column(name = "line_total", nullable = false)
    var lineTotal: Long = (unitPrice + optionExtraPerUnit) * quantity
        protected set

    @Column(name = "note", length = 500)
    var note: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: OrderItemStatus = OrderItemStatus.PENDING
        protected set

    @Column(name = "reason_code", length = 50)
    var reasonCode: String? = null
        protected set

    @Column(name = "reason_note", columnDefinition = "text")
    var reasonNote: String? = null
        protected set

    @Column(name = "wasted", nullable = false)
    var wasted: Boolean = false
        protected set

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var createdAt: OffsetDateTime? = null
        protected set

    @Column(name = "confirmed_at", columnDefinition = "timestamptz")
    var confirmedAt: OffsetDateTime? = null
        protected set

    @Column(name = "preparing_at", columnDefinition = "timestamptz")
    var preparingAt: OffsetDateTime? = null
        protected set

    @Column(name = "ready_at", columnDefinition = "timestamptz")
    var readyAt: OffsetDateTime? = null
        protected set

    @Column(name = "served_at", columnDefinition = "timestamptz")
    var servedAt: OffsetDateTime? = null
        protected set

    @Column(name = "closed_at", columnDefinition = "timestamptz")
    var closedAt: OffsetDateTime? = null
        protected set

    @Column(name = "status_changed_by")
    var statusChangedBy: Long? = null
        protected set

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    var updatedAt: OffsetDateTime? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status_changed_by_type", length = 8)
    var statusChangedByType: StatusActorType? = null
        protected set

    @Column(name = "status_changed_by_device_id", length = 64)
    var statusChangedByDeviceId: String? = null
        protected set

    /** Đổi số lượng và tính lại thành tiền dòng (BE-04). Chỉ khi món còn PENDING/CONFIRMED (BR-O05). */
    fun changeQuantity(newQuantity: Int) {
        require(newQuantity >= 1) { "Số lượng phải >= 1" }
        if (status != OrderItemStatus.PENDING && status != OrderItemStatus.CONFIRMED) {
            throw BusinessException(ErrorCode.ORDER_ITEM_NOT_EDITABLE, "Món đang $status, không thể sửa số lượng")
        }
        quantity = newQuantity
        lineTotal = (unitPrice + optionExtraPerUnit) * newQuantity
    }

    /** Món tạo ở CONFIRMED khi đợt không cần xác nhận (BR-S04). Chỉ dùng trước khi persist. */
    fun initiallyConfirmed(at: OffsetDateTime) {
        check(id == null) { "Chỉ đặt trạng thái khởi tạo trước khi lưu" }
        status = OrderItemStatus.CONFIRMED
        confirmedAt = at
    }

    /**
     * Đổi trạng thái theo §9.1 và ghi người thực hiện.
     *
     * - Chuyển không hợp lệ: INVALID_ORDER_ITEM_TRANSITION (409), giống mã của trigger.
     * - CANCELLED/REJECTED: bắt buộc [reasonCode] dạng UPPER_SNAKE_CASE (BR-O08); nếu món đang
     *   PREPARING/READY thì tự đánh dấu wasted = true (BR-O06).
     * - Ghi mốc thời gian tương ứng (CHECK ck_order_item_milestones, ck_order_item_closed_at).
     */
    fun changeStatus(
        target: OrderItemStatus,
        actor: StatusActor,
        at: OffsetDateTime,
        reasonCode: String? = null,
        reasonNote: String? = null,
    ) {
        val from = status
        if (!from.canTransitionTo(target)) {
            throw BusinessException(ErrorCode.INVALID_ORDER_ITEM_TRANSITION, "Không thể chuyển món từ $from sang $target")
        }
        if (target.isClosedWithoutServing) {
            if (reasonCode == null || !REASON_CODE.matches(reasonCode)) {
                throw BusinessException(ErrorCode.INVALID_REASON_CODE)
            }
            this.reasonCode = reasonCode
            this.reasonNote = reasonNote
            this.wasted = from.isCooking
            this.closedAt = at
        }
        when (target) {
            OrderItemStatus.CONFIRMED -> confirmedAt = at
            OrderItemStatus.PREPARING -> preparingAt = at
            OrderItemStatus.READY -> readyAt = at
            OrderItemStatus.SERVED -> servedAt = at
            else -> Unit
        }
        recordActor(actor)
        status = target
    }

    private fun recordActor(actor: StatusActor) {
        when (actor) {
            is StatusActor.Staff -> {
                statusChangedByType = StatusActorType.STAFF
                statusChangedBy = actor.userId
                statusChangedByDeviceId = null
            }
            is StatusActor.Guest -> {
                statusChangedByType = StatusActorType.GUEST
                statusChangedBy = null
                statusChangedByDeviceId = actor.deviceId
            }
            StatusActor.System -> {
                statusChangedByType = StatusActorType.SYSTEM
                statusChangedBy = null
                statusChangedByDeviceId = null
            }
        }
    }

    companion object {
        /** CHECK ck_order_item_reason_code. */
        val REASON_CODE = Regex("^[A-Z][A-Z0-9_]{1,49}$")
    }
}
