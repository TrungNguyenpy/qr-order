package com.qrorder.menu

import com.qrorder.menu.service.MenuAuditActions
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.MenuIntegrationTestBase
import com.qrorder.support.TestImages
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Upload ảnh món (FR-PRD-01, Cloudinary qua ImageStorageService giả - không gọi mạng):
 * validate định dạng/dung lượng, lưu image_url + image_public_id, xóa ảnh cũ best-effort sau commit,
 * bù trừ khi lưu DB thất bại, lỗi dịch vụ lưu ảnh, phân quyền, optimistic lock.
 */
class ProductImageIT : MenuIntegrationTestBase() {

    private fun file(content: ByteArray, contentType: String, name: String = "anh.jpg") =
        MockMultipartFile("file", name, contentType, content)

    private fun upload(productId: Long, version: Long, file: MockMultipartFile?, token: String? = admin.accessToken) =
        api.multipart("/api/v1/products/$productId/image", token, file, mapOf("version" to version.toString()))

    private fun dbImage(productId: Long): Pair<String?, String?> =
        jdbc.queryForObject("SELECT image_url, image_public_id FROM product WHERE id = ?", { rs, _ ->
            rs.getString(1) to rs.getString(2)
        }, productId)!!

    @Test
    fun `upload a JPEG - stores https url and public id, audits, increments version, publishes IMAGE_CHANGED`() {
        val pho = productId("Phở bò tái")
        val result = data(upload(pho, 0, file(TestImages.JPEG, "image/jpeg")).andExpect(status().isOk))

        val stored = imageStorage.uploads.single()
        assertEquals("image/jpeg", stored.contentType)
        assertEquals("product-$pho.jpg", stored.filename)
        assertTrue(result["imageUrl"].asText().startsWith("https://"))
        assertEquals(1L, result["version"].asLong())
        assertEquals(result["imageUrl"].asText() to stored.publicId, dbImage(pho))
        assertTrue(imageStorage.deletes.isEmpty(), "Chưa có ảnh cũ để xóa")

        val audit = api.auditRows(MenuAuditActions.PRODUCT_IMAGE_CHANGED).single()
        assertTrue(api.jsonOf(audit["old_value"])["imageUrl"].isNull)
        assertEquals(result["imageUrl"].asText(), api.jsonOf(audit["new_value"])["imageUrl"].asText())
        assertEquals("IMAGE_CHANGED", topic.messages.single()["change"].asText())
    }

    @Test
    fun `replacing an image deletes the old one after commit - delete failure is only a warning`() {
        val pho = productId("Phở bò tái")
        upload(pho, 0, file(TestImages.PNG, "image/png", "a.png"), manager.accessToken).andExpect(status().isOk)
        val first = imageStorage.uploads.single().publicId

        upload(pho, 1, file(TestImages.WEBP, "image/webp", "b.webp")).andExpect(status().isOk)
        assertEquals(listOf(first), imageStorage.deletes)
        val second = imageStorage.uploads.last().publicId
        assertEquals(second, dbImage(pho).second)

        imageStorage.failDelete = true
        upload(pho, 2, file(TestImages.JPEG, "image/jpeg")).andExpect(status().isOk)
        assertEquals(imageStorage.uploads.last().publicId, dbImage(pho).second, "Lỗi xóa ảnh cũ không làm hỏng dữ liệu đã lưu")
        assertEquals(3L, version("product", pho))
    }

    @Test
    fun `legacy image without public_id - old public id is extracted from the Cloudinary URL`() {
        val pho = productId("Phở bò tái")
        tx.executeWithoutResult {
            jdbc.update(
                "UPDATE product SET image_url = 'https://res.cloudinary.com/demo/image/upload/v1712345678/legacy/pho.jpg' WHERE id = ?",
                pho,
            )
        }
        upload(pho, 0, file(TestImages.JPEG, "image/jpeg")).andExpect(status().isOk)
        assertEquals(listOf("legacy/pho"), imageStorage.deletes)
    }

    @Test
    fun `invalid files are rejected before any upload - type, forged content, size, empty, missing part`() {
        val pho = productId("Phở bò tái")
        upload(pho, 0, file(TestImages.GIF, "image/gif", "a.gif")).isError(415, "UNSUPPORTED_IMAGE_TYPE")
        upload(pho, 0, file("<script>".toByteArray() + ByteArray(32), "image/jpeg")).isError(415, "UNSUPPORTED_IMAGE_TYPE")
        upload(pho, 0, file(TestImages.PNG, "image/jpeg")).isError(415, "UNSUPPORTED_IMAGE_TYPE")
        upload(pho, 0, file(TestImages.jpegOfSize(5 * 1024 * 1024 + 1), "image/jpeg")).isError(413, "IMAGE_TOO_LARGE")
        upload(pho, 0, file(ByteArray(0), "image/jpeg")).isError(400, "VALIDATION_FAILED")
        upload(pho, 0, null).isError(400, "MALFORMED_REQUEST")

        // Đúng 5MB vẫn hợp lệ.
        upload(pho, 0, file(TestImages.jpegOfSize(5 * 1024 * 1024), "image/jpeg")).andExpect(status().isOk)
        assertEquals(1, imageStorage.uploads.size)
    }

    @Test
    fun `stale version is rejected with 409 before uploading`() {
        val pho = productId("Phở bò tái")
        upload(pho, 7, file(TestImages.JPEG, "image/jpeg")).isError(409, "CONCURRENT_MODIFICATION")
        assertTrue(imageStorage.uploads.isEmpty(), "Không upload thừa khi version đã lệch")
        upload(999_999, 0, file(TestImages.JPEG, "image/jpeg")).isError(404, "RESOURCE_NOT_FOUND")
    }

    @Test
    fun `storage not configured returns 503, storage failure returns 502 - product unchanged`() {
        val pho = productId("Phở bò tái")
        imageStorage.configured = false
        upload(pho, 0, file(TestImages.JPEG, "image/jpeg")).isError(503, "IMAGE_STORAGE_NOT_CONFIGURED")

        imageStorage.configured = true
        imageStorage.failUpload = true
        upload(pho, 0, file(TestImages.JPEG, "image/jpeg")).isError(502, "IMAGE_UPLOAD_FAILED")

        assertEquals(null to null, dbImage(pho))
        assertEquals(0L, version("product", pho))
        assertEquals(0, api.auditRows(MenuAuditActions.PRODUCT_IMAGE_CHANGED).size)
        assertTrue(topic.messages.isEmpty())
    }

    @Test
    fun `only ADMIN and MANAGER can upload or remove images`() {
        val pho = productId("Phở bò tái")
        listOf(staff, kitchen).forEach { user ->
            upload(pho, 0, file(TestImages.JPEG, "image/jpeg"), user.accessToken).isError(403, "PERMISSION_DENIED")
            api.delete("/api/v1/products/$pho/image?version=0", user.accessToken).isError(403, "PERMISSION_DENIED")
        }
        upload(pho, 0, file(TestImages.JPEG, "image/jpeg"), null).isError(401, "UNAUTHENTICATED")
        assertTrue(imageStorage.uploads.isEmpty())
    }

    @Test
    fun `remove image clears url and public id, deletes the stored image after commit, idempotent`() {
        val pho = productId("Phở bò tái")
        upload(pho, 0, file(TestImages.JPEG, "image/jpeg")).andExpect(status().isOk)
        val publicId = imageStorage.uploads.single().publicId

        val result = data(api.delete("/api/v1/products/$pho/image?version=1", manager.accessToken).andExpect(status().isOk))
        assertTrue(result["imageUrl"].isNull)
        assertEquals(null to null, dbImage(pho))
        assertEquals(listOf(publicId), imageStorage.deletes)
        assertEquals(2, api.auditRows(MenuAuditActions.PRODUCT_IMAGE_CHANGED).size)

        api.delete("/api/v1/products/$pho/image?version=2", manager.accessToken).andExpect(status().isOk)
        assertEquals(1, imageStorage.deletes.size, "Món không còn ảnh: không làm gì")
        api.delete("/api/v1/products/$pho/image?version=0", manager.accessToken).isError(409, "CONCURRENT_MODIFICATION")
        assertNull(dbImage(pho).first)
    }
}
