package com.qrorder.menu.api

import com.qrorder.auth.security.CurrentPrincipal
import com.qrorder.common.api.ApiResponse
import com.qrorder.common.api.PageResponse
import com.qrorder.menu.domain.ProductStatus
import com.qrorder.menu.dto.CategoryResponse
import com.qrorder.menu.dto.ChangeActiveRequest
import com.qrorder.menu.dto.ChangeCategoryVisibilityRequest
import com.qrorder.menu.dto.ChangeProductStatusRequest
import com.qrorder.menu.dto.CreateCategoryRequest
import com.qrorder.menu.dto.CreateOptionGroupRequest
import com.qrorder.menu.dto.CreateOptionItemRequest
import com.qrorder.menu.dto.CreateProductRequest
import com.qrorder.menu.dto.OptionGroupResponse
import com.qrorder.menu.dto.OptionItemResponse
import com.qrorder.menu.dto.ProductResponse
import com.qrorder.menu.dto.PublicMenuResponse
import com.qrorder.menu.dto.ReorderCategoriesRequest
import com.qrorder.menu.dto.UpdateCategoryRequest
import com.qrorder.menu.dto.UpdateOptionGroupRequest
import com.qrorder.menu.dto.UpdateOptionItemRequest
import com.qrorder.menu.dto.UpdateProductOptionGroupsRequest
import com.qrorder.menu.dto.UpdateProductRequest
import com.qrorder.menu.service.CategoryService
import com.qrorder.menu.service.OptionGroupService
import com.qrorder.menu.service.ProductImageService
import com.qrorder.menu.service.ProductService
import com.qrorder.menu.service.PublicMenuService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Phân quyền API menu (Giai đoạn 2, người dùng chốt: ADMIN/MANAGER quản lý, STAFF/KITCHEN chỉ xem).
 * Ngoại lệ theo SRS FR-PRD-02, §10, UC25: STAFF/KITCHEN được đổi món AVAILABLE <-> SOLD_OUT
 * (PATCH /products/{id}/status); mọi chuyển liên quan HIDDEN vẫn chỉ ADMIN/MANAGER (kiểm tra ở ProductService).
 * Guest không dùng các API này (menu khách: GET /api/v1/public/menu).
 */
object MenuAccess {
    const val VIEW = "hasAnyRole('ADMIN','MANAGER','STAFF','KITCHEN')"
    const val MANAGE = "hasAnyRole('ADMIN','MANAGER')"
    const val CHANGE_AVAILABILITY = "hasAnyRole('ADMIN','MANAGER','STAFF','KITCHEN')"
    const val MAX_PAGE_SIZE = 100

    /** Phân trang (page từ 0) theo displayOrder rồi id. */
    fun pageable(page: Int, size: Int): Pageable =
        PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE), Sort.by("displayOrder", "id"))

    fun actorId(): Long = CurrentPrincipal.requireStaff().userId
}

/** FR-CAT-01: danh mục món. Không có xóa (BR-A03): ẩn bằng PATCH /visibility. */
@Tag(name = "Menu - Categories", description = "Danh mục món")
@RestController
@RequestMapping("/api/v1/categories")
class CategoryController(
    private val service: CategoryService,
) {
    @Operation(summary = "Danh sách danh mục (từ khóa q, lọc visible; theo displayOrder; page từ 0)")
    @GetMapping
    @PreAuthorize(MenuAccess.VIEW)
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) visible: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<CategoryResponse>> = ApiResponse.ok(service.search(q, visible, MenuAccess.pageable(page, size)))

    @Operation(summary = "Chi tiết danh mục")
    @GetMapping("/{id}")
    @PreAuthorize(MenuAccess.VIEW)
    fun get(@PathVariable id: Long): ApiResponse<CategoryResponse> = ApiResponse.ok(service.get(id))

    @Operation(summary = "Tạo danh mục")
    @PostMapping
    @PreAuthorize(MenuAccess.MANAGE)
    fun create(@Valid @RequestBody request: CreateCategoryRequest): ResponseEntity<ApiResponse<CategoryResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(MenuAccess.actorId(), request)))

    @Operation(summary = "Sửa tên, mô tả, thứ tự (gửi kèm version)")
    @PutMapping("/{id}")
    @PreAuthorize(MenuAccess.MANAGE)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateCategoryRequest): ApiResponse<CategoryResponse> =
        ApiResponse.ok(service.update(MenuAccess.actorId(), id, request))

    @Operation(summary = "Ẩn/hiện danh mục (gửi kèm version)")
    @PatchMapping("/{id}/visibility")
    @PreAuthorize(MenuAccess.MANAGE)
    fun changeVisibility(
        @PathVariable id: Long,
        @Valid @RequestBody request: ChangeCategoryVisibilityRequest,
    ): ApiResponse<CategoryResponse> = ApiResponse.ok(service.changeVisibility(MenuAccess.actorId(), id, request))

    @Operation(summary = "Sắp xếp lại nhiều danh mục (mỗi phần tử: id, displayOrder, version)")
    @PutMapping("/display-order")
    @PreAuthorize(MenuAccess.MANAGE)
    fun reorder(@Valid @RequestBody request: ReorderCategoriesRequest): ApiResponse<List<CategoryResponse>> =
        ApiResponse.ok(service.reorder(MenuAccess.actorId(), request))
}

/** FR-PRD-01..04: món, giá, trạng thái, ảnh, nhóm option. Không có xóa (BR-A03): ẩn bằng trạng thái HIDDEN. */
@Tag(name = "Menu - Products", description = "Món ăn")
@RestController
@RequestMapping("/api/v1/products")
class ProductController(
    private val service: ProductService,
    private val imageService: ProductImageService,
) {
    @Operation(summary = "Danh sách món (từ khóa q, lọc categoryId, status; theo displayOrder; page từ 0)")
    @GetMapping
    @PreAuthorize(MenuAccess.VIEW)
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(required = false) status: ProductStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<ProductResponse>> =
        ApiResponse.ok(service.search(q, categoryId, status, MenuAccess.pageable(page, size)))

    @Operation(summary = "Chi tiết món kèm nhóm option đã gán")
    @GetMapping("/{id}")
    @PreAuthorize(MenuAccess.VIEW)
    fun get(@PathVariable id: Long): ApiResponse<ProductResponse> = ApiResponse.ok(service.get(id))

    @Operation(summary = "Tạo món (giá VND đã gồm VAT, >= 0)")
    @PostMapping
    @PreAuthorize(MenuAccess.MANAGE)
    fun create(@Valid @RequestBody request: CreateProductRequest): ResponseEntity<ApiResponse<ProductResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(MenuAccess.actorId(), request)))

    @Operation(summary = "Sửa thông tin và giá (gửi kèm version; đổi giá ghi audit PRODUCT_PRICE_CHANGED)")
    @PutMapping("/{id}")
    @PreAuthorize(MenuAccess.MANAGE)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateProductRequest): ApiResponse<ProductResponse> =
        ApiResponse.ok(service.update(MenuAccess.actorId(), id, request))

    @Operation(summary = "Đổi trạng thái AVAILABLE/SOLD_OUT (Bếp, Staff) hoặc HIDDEN (ADMIN, MANAGER)")
    @PatchMapping("/{id}/status")
    @PreAuthorize(MenuAccess.CHANGE_AVAILABILITY)
    fun changeStatus(@PathVariable id: Long, @Valid @RequestBody request: ChangeProductStatusRequest): ApiResponse<ProductResponse> =
        ApiResponse.ok(service.changeStatus(CurrentPrincipal.requireStaff(), id, request))

    @Operation(summary = "Gán/gỡ/sắp xếp nhóm option của món (danh sách đầy đủ theo thứ tự, gửi kèm version của món)")
    @PutMapping("/{id}/option-groups")
    @PreAuthorize(MenuAccess.MANAGE)
    fun updateOptionGroups(
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateProductOptionGroupsRequest,
    ): ApiResponse<ProductResponse> = ApiResponse.ok(service.updateOptionGroups(MenuAccess.actorId(), id, request))

    @Operation(summary = "Upload ảnh món (multipart: file JPEG/PNG/WebP tối đa 5MB; query version)")
    @PostMapping("/{id}/image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize(MenuAccess.MANAGE)
    fun uploadImage(
        @PathVariable id: Long,
        @RequestParam version: Long,
        @RequestPart("file") file: MultipartFile,
    ): ApiResponse<ProductResponse> =
        ApiResponse.ok(imageService.upload(MenuAccess.actorId(), id, version, file.bytes, file.contentType))

    @Operation(summary = "Gỡ ảnh món (query version); ảnh trên Cloudinary xóa best-effort sau khi lưu")
    @DeleteMapping("/{id}/image")
    @PreAuthorize(MenuAccess.MANAGE)
    fun removeImage(@PathVariable id: Long, @RequestParam version: Long): ApiResponse<ProductResponse> =
        ApiResponse.ok(imageService.remove(MenuAccess.actorId(), id, version))
}

/** FR-PRD-03: nhóm option/topping và lựa chọn. Không có xóa (BR-A03): ngừng dùng bằng PATCH /active. */
@Tag(name = "Menu - Option groups", description = "Nhóm option/topping và lựa chọn")
@RestController
@RequestMapping("/api/v1/option-groups")
class OptionGroupController(
    private val service: OptionGroupService,
) {
    @Operation(summary = "Danh sách nhóm option kèm lựa chọn (từ khóa q, lọc active; page từ 0)")
    @GetMapping
    @PreAuthorize(MenuAccess.VIEW)
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<OptionGroupResponse>> = ApiResponse.ok(service.search(q, active, MenuAccess.pageable(page, size)))

    @Operation(summary = "Chi tiết nhóm option kèm lựa chọn")
    @GetMapping("/{id}")
    @PreAuthorize(MenuAccess.VIEW)
    fun get(@PathVariable id: Long): ApiResponse<OptionGroupResponse> = ApiResponse.ok(service.get(id))

    @Operation(summary = "Tạo nhóm option (SINGLE/MULTIPLE, required, minSelect, maxSelect)")
    @PostMapping
    @PreAuthorize(MenuAccess.MANAGE)
    fun create(@Valid @RequestBody request: CreateOptionGroupRequest): ResponseEntity<ApiResponse<OptionGroupResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(MenuAccess.actorId(), request)))

    @Operation(summary = "Sửa nhóm option (gửi kèm version)")
    @PutMapping("/{id}")
    @PreAuthorize(MenuAccess.MANAGE)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateOptionGroupRequest): ApiResponse<OptionGroupResponse> =
        ApiResponse.ok(service.update(MenuAccess.actorId(), id, request))

    @Operation(summary = "Ngừng dùng / dùng lại nhóm option (gửi kèm version)")
    @PatchMapping("/{id}/active")
    @PreAuthorize(MenuAccess.MANAGE)
    fun changeActive(@PathVariable id: Long, @Valid @RequestBody request: ChangeActiveRequest): ApiResponse<OptionGroupResponse> =
        ApiResponse.ok(service.changeActive(MenuAccess.actorId(), id, request))

    @Operation(summary = "Thêm lựa chọn vào nhóm (phụ thu extraPrice VND >= 0)")
    @PostMapping("/{groupId}/items")
    @PreAuthorize(MenuAccess.MANAGE)
    fun createItem(
        @PathVariable groupId: Long,
        @Valid @RequestBody request: CreateOptionItemRequest,
    ): ResponseEntity<ApiResponse<OptionItemResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createItem(MenuAccess.actorId(), groupId, request)))

    @Operation(summary = "Sửa lựa chọn (gửi kèm version)")
    @PutMapping("/{groupId}/items/{itemId}")
    @PreAuthorize(MenuAccess.MANAGE)
    fun updateItem(
        @PathVariable groupId: Long,
        @PathVariable itemId: Long,
        @Valid @RequestBody request: UpdateOptionItemRequest,
    ): ApiResponse<OptionItemResponse> = ApiResponse.ok(service.updateItem(MenuAccess.actorId(), groupId, itemId, request))

    @Operation(summary = "Ngừng dùng / dùng lại lựa chọn (gửi kèm version)")
    @PatchMapping("/{groupId}/items/{itemId}/active")
    @PreAuthorize(MenuAccess.MANAGE)
    fun changeItemActive(
        @PathVariable groupId: Long,
        @PathVariable itemId: Long,
        @Valid @RequestBody request: ChangeActiveRequest,
    ): ApiResponse<OptionItemResponse> = ApiResponse.ok(service.changeItemActive(MenuAccess.actorId(), groupId, itemId, request))
}

/** Thực đơn công khai cho Customer Web (không cần đăng nhập: mọi đường dẫn dưới /api/v1/public đều permitAll). */
@Tag(name = "Public - Menu", description = "Thực đơn cho khách")
@RestController
@RequestMapping("/api/v1/public/menu")
class PublicMenuController(
    private val service: PublicMenuService,
) {
    @Operation(summary = "Thực đơn: danh mục hiển thị, món không HIDDEN (SOLD_OUT có available = false), option đang dùng")
    @GetMapping
    fun get(): ApiResponse<PublicMenuResponse> = ApiResponse.ok(service.getMenu())
}
