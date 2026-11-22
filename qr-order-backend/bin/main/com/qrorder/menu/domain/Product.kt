package com.qrorder.menu.domain

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

/** FR-PRD-02, DEC-09 A: chỉ đổi thủ công, không quản lý tồn kho. */
enum class ProductStatus { AVAILABLE, SOLD_OUT, HIDDEN }

/**
 * Món. price là giá VND đã gồm VAT (DEC-10 A, BIGINT). Ảnh lưu trên Cloudinary
 * (imageUrl https + imagePublicId để thay/xóa).
 */
@Entity
@Table(name = "product")
class Product(
    @Column(name = "category_id", nullable = false)
    var categoryId: Long,

    @Column(name = "name", nullable = false, length = 150)
    var name: String,

    @Column(name = "price", nullable = false)
    var price: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Column(name = "description", columnDefinition = "text")
    var description: String? = null

    @Column(name = "image_url", length = 500)
    var imageUrl: String? = null

    @Column(name = "image_public_id", length = 255)
    var imagePublicId: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: ProductStatus = ProductStatus.AVAILABLE

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0

    @Column(name = "status_changed_at", columnDefinition = "timestamptz")
    var statusChangedAt: OffsetDateTime? = null

    @Column(name = "status_changed_by")
    var statusChangedBy: Long? = null

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
