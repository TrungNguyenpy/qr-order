package com.qrorder.menu.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
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
import java.io.Serializable
import java.time.OffsetDateTime

/** SINGLE: chọn 1 (max_select = 1); MULTIPLE: chọn nhiều (BR-O03). */
enum class SelectionType { SINGLE, MULTIPLE }

/**
 * Nhóm option/topping dùng chung nhiều món (FR-PRD-03). Ràng buộc BR-O03 nằm ở CHECK:
 * required <=> minSelect >= 1; SINGLE => maxSelect = 1.
 */
@Entity
@Table(name = "option_group")
class OptionGroup(
    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_type", nullable = false, length = 16)
    var selectionType: SelectionType,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Column(name = "is_required", nullable = false)
    var required: Boolean = false

    @Column(name = "min_select", nullable = false)
    var minSelect: Short = 0

    @Column(name = "max_select")
    var maxSelect: Short? = null

    @Column(name = "is_active", nullable = false)
    var active: Boolean = true

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var createdAt: OffsetDateTime? = null
        protected set

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    var updatedAt: OffsetDateTime? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set
}

@Entity
@Table(name = "option_item")
class OptionItem(
    @Column(name = "option_group_id", nullable = false)
    var optionGroupId: Long,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    /** Phụ thu VND đã gồm VAT; được snapshot vào order_item_option khi đặt (BR-O01). */
    @Column(name = "extra_price", nullable = false)
    var extraPrice: Long = 0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Column(name = "is_active", nullable = false)
    var active: Boolean = true

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0

    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var createdAt: OffsetDateTime? = null
        protected set

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    var updatedAt: OffsetDateTime? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set
}

@Embeddable
data class ProductOptionGroupId(
    @Column(name = "product_id", nullable = false)
    val productId: Long = 0,

    @Column(name = "option_group_id", nullable = false)
    val optionGroupId: Long = 0,
) : Serializable

/**
 * Bảng nối N-N product - option_group. Là cấu hình nên được xóa liên kết (không có trigger chặn);
 * đơn cũ không ảnh hưởng nhờ snapshot (BR-O01).
 */
@Entity
@Table(name = "product_option_group")
class ProductOptionGroup(
    @EmbeddedId
    var id: ProductOptionGroupId,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,
) {
    @Generated(event = [EventType.INSERT])
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false, columnDefinition = "timestamptz")
    var createdAt: OffsetDateTime? = null
        protected set
}
