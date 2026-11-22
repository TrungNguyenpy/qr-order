package com.qrorder.menu.image

/** Ảnh đã lưu: [url] là URL https công khai (CHECK ck_product_image_url), [publicId] dùng để xóa/thay. */
data class StoredImage(
    val url: String,
    val publicId: String,
)

/** Lỗi khi gọi dịch vụ lưu ảnh (mạng, Cloudinary trả lỗi, phản hồi thiếu trường). Không chứa secret. */
open class ImageStorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Chưa cấu hình CLOUDINARY_CLOUD_NAME / CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET. */
class ImageStorageNotConfiguredException : ImageStorageException("Chưa cấu hình dịch vụ lưu ảnh")

/**
 * Cổng lưu trữ ảnh món (FR-PRD-01). Bản chạy thật: [CloudinaryImageStorageService].
 * Tách interface để Unit/Integration Test thay bằng bản giả, không gọi mạng thật.
 *
 * Phía gọi (ProductImageService) không gọi hai hàm này bên trong transaction database:
 * upload trước khi mở transaction, xóa ảnh cũ sau khi commit (best-effort).
 */
interface ImageStorageService {
    /** Đã cấu hình đủ để upload/xóa chưa. */
    fun isConfigured(): Boolean

    /**
     * Upload ảnh đã được kiểm tra định dạng/dung lượng.
     * @throws ImageStorageNotConfiguredException khi chưa cấu hình.
     * @throws ImageStorageException khi dịch vụ lỗi.
     */
    fun upload(content: ByteArray, contentType: String, filename: String): StoredImage

    /**
     * Xóa ảnh theo publicId. Ảnh không còn tồn tại được coi là thành công (idempotent).
     * @throws ImageStorageException khi dịch vụ lỗi (phía gọi chỉ ghi log cảnh báo).
     */
    fun delete(publicId: String)
}
