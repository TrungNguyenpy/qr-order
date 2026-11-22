package com.qrorder.support

import com.qrorder.menu.image.ImageStorageException
import com.qrorder.menu.image.ImageStorageService
import com.qrorder.menu.image.StoredImage
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bản giả của [ImageStorageService] cho integration test: không gọi mạng, ghi lại mọi lần upload/xóa và cho phép
 * giả lập lỗi. Được đăng ký @Primary cho mọi IT (IntegrationTestBase) nên bean Cloudinary thật không được dùng.
 * Gọi [reset] trước mỗi test.
 */
class InMemoryImageStorageService : ImageStorageService {
    data class Upload(val publicId: String, val contentType: String, val filename: String, val size: Int)

    val uploads = CopyOnWriteArrayList<Upload>()
    val deletes = CopyOnWriteArrayList<String>()
    private val counter = AtomicInteger()

    @Volatile var configured: Boolean = true
    @Volatile var failUpload: Boolean = false
    @Volatile var failDelete: Boolean = false

    fun reset() {
        uploads.clear()
        deletes.clear()
        configured = true
        failUpload = false
        failDelete = false
    }

    override fun isConfigured(): Boolean = configured

    override fun upload(content: ByteArray, contentType: String, filename: String): StoredImage {
        if (failUpload) throw ImageStorageException("Lỗi giả lập khi upload")
        val publicId = "test/products/img-${counter.incrementAndGet()}"
        uploads += Upload(publicId, contentType, filename, content.size)
        return StoredImage("https://res.cloudinary.com/test-cloud/image/upload/v1/$publicId.jpg", publicId)
    }

    override fun delete(publicId: String) {
        if (failDelete) throw ImageStorageException("Lỗi giả lập khi xóa")
        deletes += publicId
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestImageStorageConfig {
    @Bean
    @Primary
    fun inMemoryImageStorageService(): InMemoryImageStorageService = InMemoryImageStorageService()
}
