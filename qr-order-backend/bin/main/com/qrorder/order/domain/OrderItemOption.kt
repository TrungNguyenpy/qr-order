package com.qrorder.order.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Generated
import org.hibernate.annotations.Immutable
import org.hibernate.generator.EventType
import java.time.OffsetDateTime

/** Snapshot option tại thời điểm đặt (DEC-12 B, BR-O01). Chỉ thêm, không sửa (trigger append-only). */
@Entity
@Immutable
@Table(name = "order_item_option")
class OrderItemOption(
    @Column(name = "order_item_id", nullable = false)
    var orderItemId: Long,

    @Column(name = "option_group_id", nullable = false)
    var optionGroupId: Long,

    @Column(name = "option_item_id", nullable = false)
    var optionItemId: Long,

    @Column(name = "group_name", nullable = false, length = 100)
    var groupName: String,

    @Column(name = "option_name", nullable = false, length = 100)
    var optionName: String,

    @Column(name = "extra_price", nullable = false)
    var extraPrice: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var createdAt: OffsetDateTime? = null
        protected set
}
