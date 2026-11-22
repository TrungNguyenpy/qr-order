package com.qrorder.menu.image

import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.support.TestImages
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ImageUploadValidatorTest {
    private val max = 5L * 1024 * 1024

    private fun rejected(content: ByteArray, type: String?): ErrorCode =
        assertFailsWith<BusinessException> { ImageUploadValidator.validate(content, type, max) }.errorCode

    @Test
    fun `accepts JPEG, PNG and WebP whose content matches the declared type`() {
        assertEquals("jpg", ImageUploadValidator.validate(TestImages.JPEG, "image/jpeg", max).extension)
        assertEquals("png", ImageUploadValidator.validate(TestImages.PNG, "image/png", max).extension)
        assertEquals("webp", ImageUploadValidator.validate(TestImages.WEBP, "image/webp", max).extension)
        // Biến thể Content-Type phổ biến.
        assertEquals("image/jpeg", ImageUploadValidator.validate(TestImages.JPEG, "IMAGE/JPG; charset=binary", max).contentType)
    }

    @Test
    fun `rejects unsupported or forged types with 415`() {
        assertEquals(ErrorCode.UNSUPPORTED_IMAGE_TYPE, rejected(TestImages.GIF, "image/gif"))
        assertEquals(ErrorCode.UNSUPPORTED_IMAGE_TYPE, rejected(TestImages.GIF, "image/jpeg"))
        assertEquals(ErrorCode.UNSUPPORTED_IMAGE_TYPE, rejected(TestImages.PNG, "image/webp"))
        assertEquals(ErrorCode.UNSUPPORTED_IMAGE_TYPE, rejected(TestImages.JPEG, null))
        assertEquals(ErrorCode.UNSUPPORTED_IMAGE_TYPE, rejected(TestImages.JPEG, "application/octet-stream"))
        assertEquals(ErrorCode.UNSUPPORTED_IMAGE_TYPE, rejected("RIFF0000WAVE".toByteArray(), "image/webp"))
    }

    @Test
    fun `size limit is inclusive - exactly 5MB passes, one byte more is 413, empty is 400`() {
        ImageUploadValidator.validate(TestImages.jpegOfSize(max.toInt()), "image/jpeg", max)
        assertEquals(ErrorCode.IMAGE_TOO_LARGE, rejected(TestImages.jpegOfSize(max.toInt() + 1), "image/jpeg"))
        assertEquals(ErrorCode.VALIDATION_FAILED, rejected(ByteArray(0), "image/jpeg"))
    }

    @Test
    fun `detect returns null for short or unknown content`() {
        assertNull(ImageUploadValidator.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(ImageUploadValidator.detect("hello".toByteArray()))
    }
}
