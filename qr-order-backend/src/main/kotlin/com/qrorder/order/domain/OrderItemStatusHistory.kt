package com.qrorder.order.domain

import com.qrorder.common.domain.StatusActorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.OffsetDateTime

/**
 * Lịch sử chuyển trạng thái món (BR-O09, DB-02). CHỈ ĐỌC: do trigger `trg_order_item_status_history`
 * ghi; ứng dụng không insert. Mọi cột dữ liệu insertable = false nên nếu lỡ persist entity này thì câu
 * INSERT vi phạm NOT NULL và thất bại thay vì ghi lịch sử giả.
 */
@Entity
@Immutable
@Table(name = "order_item_status_history")
class OrderItemStatusHistory protected constructor() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Column(name = "order_item_id", nullable = false, insertable = false, updatable = false)
    var orderItemId: Long = 0
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 16, insertable = false, updatable = false)
    var fromStatus: OrderItemStatus? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 16, insertable = false, updatable = false)
    var toStatus: OrderItemStatus = OrderItemStatus.PENDING
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_type", nullable = false, length = 8, insertable = false, updatable = false)
    var changedByType: StatusActorType = StatusActorType.SYSTEM
        protected set

    @Column(name = "changed_by_user_id", insertable = false, updatable = false)
    var changedByUserId: Long? = null
        protected set

    @Column(name = "device_id", length = 64, insertable = false, updatable = false)
    var deviceId: String? = null
        protected set

    @Column(name = "reason_code", length = 50, insertable = false, updatable = false)
    var reasonCode: String? = null
        protected set

    @Column(name = "changed_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var changedAt: OffsetDateTime? = null
        protected set
}
