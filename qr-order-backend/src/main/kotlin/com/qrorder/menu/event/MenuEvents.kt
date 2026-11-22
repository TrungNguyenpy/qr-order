package com.qrorder.menu.event

import com.qrorder.menu.domain.ProductStatus

/**
 * Domain Event nội bộ (SRS §11.2, §11.3: in-process, ApplicationEventPublisher) do module `menu` phát.
 * Module khác chỉ lắng nghe, không gọi ngược vào repository/entity của `menu`.
 */

/**
 * Món vừa được chuyển sang SOLD_OUT (FR-PRD-02, BR-O13, SRS §3.4, §7.4).
 *
 * Phát bởi `ProductService.changeStatus` NGAY SAU khi lưu (saveAndFlush) trạng thái mới, BÊN TRONG transaction
 * đổi trạng thái; chỉ phát khi trạng thái cũ khác SOLD_OUT.
 *
 * Hợp đồng cho module `order` (Giai đoạn 4) - không sửa lại code của `menu`:
 * - Listener tự REJECTED (reasonCode OUT_OF_STOCK, actor SYSTEM, BR-O09) các order_item PENDING/CONFIRMED
 *   của [productId] thuộc Order đang OPEN (DATABASE_DESIGN.md §5.3 (3): Order LOCKED giữ nguyên vì BR-O12).
 * - Chọn pha giao dịch ở GĐ4: `@TransactionalEventListener(phase = BEFORE_COMMIT)` (hoặc `@EventListener`) để
 *   việc REJECTED cùng commit/rollback với SOLD_OUT; hoặc `AFTER_COMMIT` + `Propagation.REQUIRES_NEW` nếu muốn
 *   SOLD_OUT không phụ thuộc kết quả REJECTED. Mặc định đề xuất BEFORE_COMMIT (nhất quán dữ liệu, NFR-05).
 * - [changedByUserId] là nhân viên bấm SOLD_OUT; dùng cho audit, không dùng làm actor của lịch sử món
 *   (lịch sử món ghi SYSTEM vì là hệ quả tự động).
 */
data class ProductSoldOutEvent(
    val productId: Long,
    val changedByUserId: Long,
)

/** Loại đối tượng menu thay đổi. */
enum class MenuEntityType { CATEGORY, PRODUCT, OPTION_GROUP, OPTION_ITEM }

/** Loại thay đổi (client dùng để quyết định cập nhật tại chỗ hay tải lại GET /api/v1/public/menu). */
enum class MenuChangeKind {
    CREATED,
    UPDATED,
    PRICE_CHANGED,
    STATUS_CHANGED,
    IMAGE_CHANGED,
    VISIBILITY_CHANGED,
    ACTIVE_CHANGED,
    REORDERED,
    OPTIONS_CHANGED,
}

/**
 * Menu thay đổi (Category, Product, OptionGroup, OptionItem, liên kết product-option).
 * Module `notification` lắng nghe bằng `@TransactionalEventListener(AFTER_COMMIT)` rồi đẩy `/topic/menu`
 * (SRS §11.4), nên transaction bị rollback thì không có thông báo nào được gửi.
 *
 * [entityId] null khi thay đổi nhiều đối tượng một lúc (sắp xếp lại danh mục).
 * [productStatus], [price] chỉ có với PRODUCT để client cập nhật nhanh (món SOLD_OUT hiển thị mờ).
 */
data class MenuChangedEvent(
    val entityType: MenuEntityType,
    val entityId: Long?,
    val change: MenuChangeKind,
    val version: Long? = null,
    val productStatus: ProductStatus? = null,
    val price: Long? = null,
)
