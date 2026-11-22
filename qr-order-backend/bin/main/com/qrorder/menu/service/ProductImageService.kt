package com.qrorder.menu.service

import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.menu.domain.Product
import com.qrorder.menu.dto.ProductResponse
import com.qrorder.menu.event.MenuChangeKind
import com.qrorder.menu.image.CloudinaryUrls
import com.qrorder.menu.image.ImageStorageException
import com.qrorder.menu.image.ImageStorageNotConfiguredException
import com.qrorder.menu.image.ImageStorageService
import com.qrorder.menu.image.ImageUploadValidator
import com.qrorder.menu.image.MenuImageProperties
import com.qrorder.menu.image.StoredImage
import com.qrorder.menu.repository.ProductRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Ảnh món trên Cloudinary (FR-PRD-01). Không gọi mạng bên trong transaction database:
 *
 * 1. Kiểm tra file (JPEG/PNG/WebP theo Content-Type và chữ ký nhị phân, tối đa `app.menu.image.max-size`).
 * 2. Kiểm tra sớm món tồn tại + version (tránh upload thừa).
 * 3. Upload lên Cloudinary (ngoài transaction). Lỗi -> 502 IMAGE_UPLOAD_FAILED; chưa cấu hình -> 503.
 * 4. Transaction: kiểm tra lại version, lưu `image_url` + `image_public_id`, audit PRODUCT_IMAGE_CHANGED, phát
 *    MenuChangedEvent. Transaction lỗi -> xóa ảnh vừa upload (bù trừ, best-effort).
 * 5. Sau commit: xóa ảnh cũ theo cơ chế best-effort - lỗi chỉ ghi log cảnh báo, không ảnh hưởng dữ liệu đã lưu.
 *
 * public_id của ảnh cũ lấy từ cột `product.image_public_id` (có sẵn trong schema V1); nếu trống (dữ liệu nhập tay/cũ)
 * thì trích từ URL Cloudinary ([CloudinaryUrls.extractPublicId]). Không cần migration V4.
 */
@Service
class ProductImageService(
    private val repository: ProductRepository,
    private val productService: ProductService,
    private val imageStorage: ImageStorageService,
    private val imageProperties: MenuImageProperties,
    private val auditService: AuditService,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val readTx = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    fun upload(
        actorUserId: Long,
        productId: Long,
        version: Long,
        content: ByteArray,
        declaredContentType: String?,
    ): ProductResponse {
        val image = ImageUploadValidator.validate(content, declaredContentType, imageProperties.maxSize.toBytes())
        readTx.executeWithoutResult { MenuGuards.requireVersion(version, load(productId).version) }
        if (!imageStorage.isConfigured()) throw BusinessException(ErrorCode.IMAGE_STORAGE_NOT_CONFIGURED)

        val stored = try {
            imageStorage.upload(image.content, image.contentType, "product-$productId.${image.extension}")
        } catch (ex: ImageStorageNotConfiguredException) {
            throw BusinessException(ErrorCode.IMAGE_STORAGE_NOT_CONFIGURED)
        } catch (ex: ImageStorageException) {
            throw BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED, cause = ex)
        }

        val (response, oldPublicId) = try {
            requireNotNull(tx.execute { replaceImage(actorUserId, productId, version, stored) })
        } catch (ex: Exception) {
            // Không lưu được vào DB (409, lỗi DB...): ảnh vừa upload thành ảnh mồ côi -> xóa bù trừ.
            deleteQuietly(stored.publicId, "bù trừ sau khi lưu ảnh món #$productId thất bại")
            throw ex
        }
        if (oldPublicId != null && oldPublicId != stored.publicId) deleteQuietly(oldPublicId, "ảnh cũ của món #$productId")
        return response
    }

    /** Gỡ ảnh khỏi món; món không có ảnh thì không đổi gì. Ảnh trên Cloudinary xóa sau commit (best-effort). */
    fun remove(actorUserId: Long, productId: Long, version: Long): ProductResponse {
        val (response, oldPublicId) = requireNotNull(tx.execute { replaceImage(actorUserId, productId, version, null) })
        if (oldPublicId != null) deleteQuietly(oldPublicId, "ảnh đã gỡ của món #$productId")
        return response
    }

    /** Chạy trong transaction: trả về (món sau cập nhật, public_id ảnh cũ cần xóa). */
    private fun replaceImage(actorUserId: Long, productId: Long, version: Long, stored: StoredImage?): Pair<ProductResponse, String?> {
        val product = load(productId)
        MenuGuards.requireVersion(version, product.version)
        if (stored == null && product.imageUrl == null && product.imagePublicId == null) {
            return productService.toDetail(product) to null
        }
        val oldUrl = product.imageUrl
        val oldPublicId = product.imagePublicId ?: CloudinaryUrls.extractPublicId(oldUrl)
        product.imageUrl = stored?.url
        product.imagePublicId = stored?.publicId
        val saved = repository.saveAndFlush(product)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = MenuAuditActions.PRODUCT_IMAGE_CHANGED,
                entityType = MenuAuditEntityTypes.PRODUCT,
                entityId = productId,
                oldValue = mapOf("imageUrl" to oldUrl),
                newValue = mapOf("imageUrl" to saved.imageUrl),
            ),
        )
        productService.publish(saved, MenuChangeKind.IMAGE_CHANGED)
        return productService.toDetail(saved) to oldPublicId
    }

    private fun load(id: Long): Product = repository.findById(id).orElseThrow { ResourceNotFoundException("món", id) }

    /** Xóa best-effort: mọi lỗi chỉ ghi log cảnh báo (không có secret trong log). */
    private fun deleteQuietly(publicId: String, context: String) {
        if (!imageStorage.isConfigured()) {
            log.warn("Bỏ qua xóa {} ({}): chưa cấu hình dịch vụ lưu ảnh", publicId, context)
            return
        }
        try {
            imageStorage.delete(publicId)
        } catch (ex: Exception) {
            log.warn("Không xóa được {} trên dịch vụ lưu ảnh ({}): {}", publicId, context, ex.message)
        }
    }
}
