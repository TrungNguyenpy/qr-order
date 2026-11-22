package com.qrorder.menu.image

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CloudinarySignerTest {
    @Test
    fun `matches the example in Cloudinary documentation (timestamp only, secret abcd)`() {
        assertEquals("a21ad0f63beb4de2e5575204b79ab90bffb02c10", CloudinarySigner.sign(mapOf("timestamp" to "1315060510"), "abcd"))
    }

    @Test
    fun `excludes file, api_key, cloud_name, resource_type and empty values, sorts parameters`() {
        val expected = CloudinarySigner.sign(mapOf("folder" to "a", "timestamp" to "1"), "s")
        val actual = CloudinarySigner.sign(
            linkedMapOf("timestamp" to "1", "api_key" to "k", "file" to "f", "cloud_name" to "c", "resource_type" to "image", "folder" to "a", "tags" to ""),
            "s",
        )
        assertEquals(expected, actual)
    }
}

class CloudinaryUrlsTest {
    @Test
    fun `extracts public id with folders, version and transformations`() {
        assertEquals("qr-order/products/abc123", CloudinaryUrls.extractPublicId("https://res.cloudinary.com/demo/image/upload/v1712345678/qr-order/products/abc123.jpg"))
        assertEquals("sample", CloudinaryUrls.extractPublicId("https://res.cloudinary.com/demo/image/upload/c_fill,w_300/v42/sample.webp"))
        assertEquals("sample", CloudinaryUrls.extractPublicId("https://res.cloudinary.com/demo/image/upload/sample.png"))
        assertEquals("folder/món ăn", CloudinaryUrls.extractPublicId("https://res.cloudinary.com/demo/image/upload/v1/folder/m%C3%B3n%20%C4%83n.jpg"))
    }

    @Test
    fun `returns null for non Cloudinary or malformed urls`() {
        assertNull(CloudinaryUrls.extractPublicId(null))
        assertNull(CloudinaryUrls.extractPublicId("https://example.com/image/upload/v1/x.jpg"))
        assertNull(CloudinaryUrls.extractPublicId("https://res.cloudinary.com/demo/image/fetch/x.jpg"))
        assertNull(CloudinaryUrls.extractPublicId("https://res.cloudinary.com/demo/image/upload/v1/"))
        assertNull(CloudinaryUrls.extractPublicId("not a url"))
    }
}

/** Gọi Upload API qua MockRestServiceServer - không có mạng thật. */
class CloudinaryImageStorageServiceTest {
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC)
    private val properties = CloudinaryProperties(cloudName = "demo", apiKey = "key-123", apiSecret = "secret-xyz", folder = "qr/products")

    private fun service(props: CloudinaryProperties = properties): Pair<CloudinaryImageStorageService, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return CloudinaryImageStorageService(props, builder.build(), clock) to server
    }

    @Test
    fun `upload sends a signed multipart request and returns secure_url and public_id`() {
        val (svc, server) = service()
        val signature = CloudinarySigner.sign(mapOf("folder" to "qr/products", "timestamp" to "1700000000"), "secret-xyz")
        server.expect(requestTo("https://api.cloudinary.com/v1_1/demo/image/upload"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string(containsString(signature)))
            .andExpect(content().string(containsString("key-123")))
            .andExpect(content().string(not(containsString("secret-xyz"))))
            .andRespond(
                withSuccess(
                    """{"public_id":"qr/products/p1","secure_url":"https://res.cloudinary.com/demo/image/upload/v1/qr/products/p1.jpg"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val stored = svc.upload(byteArrayOf(1, 2, 3), "image/jpeg", "product-1.jpg")
        assertEquals("qr/products/p1", stored.publicId)
        assertTrue(stored.url.startsWith("https://"))
        server.verify()
    }

    @Test
    fun `upload errors and incomplete responses become ImageStorageException`() {
        val (svc, server) = service()
        server.expect(requestTo("https://api.cloudinary.com/v1_1/demo/image/upload")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        assertFailsWith<ImageStorageException> { svc.upload(byteArrayOf(1), "image/png", "a.png") }

        val (svc2, server2) = service()
        server2.expect(requestTo("https://api.cloudinary.com/v1_1/demo/image/upload"))
            .andRespond(withSuccess("""{"public_id":"x","url":"http://insecure/x.jpg"}""", MediaType.APPLICATION_JSON))
        assertFailsWith<ImageStorageException> { svc2.upload(byteArrayOf(1), "image/png", "a.png") }
        server.verify()
        server2.verify()
    }

    @Test
    fun `delete treats ok and not found as success, anything else as failure`() {
        val (svc, server) = service()
        val url = "https://api.cloudinary.com/v1_1/demo/image/destroy"
        server.expect(requestTo(url)).andExpect(content().string(containsString("public_id=qr%2Fproducts%2Fp1")))
            .andRespond(withSuccess("""{"result":"ok"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo(url)).andRespond(withSuccess("""{"result":"not found"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo(url)).andRespond(withSuccess("""{"result":"error"}""", MediaType.APPLICATION_JSON))
        svc.delete("qr/products/p1")
        svc.delete("gone")
        assertFailsWith<ImageStorageException> { svc.delete("x") }
        server.verify()
    }

    @Test
    fun `not configured - no request is sent and the secret never appears in toString`() {
        val (svc, server) = service(CloudinaryProperties())
        assertFalse(svc.isConfigured())
        assertFailsWith<ImageStorageNotConfiguredException> { svc.upload(byteArrayOf(1), "image/png", "a.png") }
        assertFailsWith<ImageStorageNotConfiguredException> { svc.delete("x") }
        server.verify()
        assertFalse(properties.toString().contains("secret-xyz"))
        assertFalse(properties.toString().contains("key-123"))
    }
}
