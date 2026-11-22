package com.qrorder.menu.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.Generated
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.generator.EventType
import java.time.OffsetDateTime

/** Danh mục món (FR-CAT-01). Ẩn bằng visible, không xóa cứng (BR-A03). */
@Entity
@Table(name = "category")
class Category(
    @Column(name = "name", nullable = false, length = 100)
    var name: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Column(name = "description", columnDefinition = "text")
    var description: String? = null

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0

    @Column(name = "is_visible", nullable = false)
    var visible: Boolean = true

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
