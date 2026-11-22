package com.qrorder.menu

import com.qrorder.common.error.ErrorCode
import com.qrorder.menu.event.ProductSoldOutEvent
import com.qrorder.menu.service.MenuAuditActions
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.MenuIntegrationTestBase
import com.qrorder.support.RecordingEventListener
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * FR-PRD-01..04: CRUD món, tìm kiếm/lọc, giá (audit khi đổi), trạng thái AVAILABLE/SOLD_OUT/HIDDEN (phân quyền,
 * ProductSoldOutEvent), gán/gỡ/sắp xếp nhóm option, optimistic lock, ràng buộc DB của bảng product.
 */
class ProductApiIT : MenuIntegrationTestBase() {

    private val soldOut = RecordingEventListener(ProductSoldOutEvent::class.java)

    @BeforeEach
    fun registerListener() {
        soldOut.register(eventMulticaster)
    }

    @AfterEach
    fun unregisterListener() {
        soldOut.unregister(eventMulticaster)
    }

    private fun statusBody(status: String, version: Long, reason: String? = null) =
        mapOf("status" to status, "version" to version, "reason" to reason)

    @Test
    fun `create product - defaults, price 0 allowed, audit, event, invalid input rejected`() {
        val drinks = categoryId("Đồ uống")
        val created = data(
            api.post(
                "/api/v1/products", manager.accessToken,
                mapOf("categoryId" to drinks, "name" to " Nước suối ", "price" to 0, "description" to "Chai 500ml"),
            ).andExpect(status().isCreated),
        )
        assertEquals("Nước suối", created["name"].asText())
        assertEquals(0L, created["price"].asLong())
        assertEquals("AVAILABLE", created["status"].asText())
        assertEquals(manager.id, created["statusChangedBy"].asLong())
        assertTrue(created["optionGroups"].isArray && created["optionGroups"].isEmpty)
        assertTrue(created["imageUrl"].isNull)

        val audit = api.auditRows(MenuAuditActions.PRODUCT_CREATED).single()
        assertEquals("product", audit["entity_type"])
        assertEquals(0L, api.jsonOf(audit["new_value"])["price"].asLong())
        val event = topic.messages.single()
        assertEquals("PRODUCT", event["entityType"].asText())
        assertEquals("AVAILABLE", event["productStatus"].asText())
        assertEquals(0L, event["price"].asLong())

        api.post("/api/v1/products", admin.accessToken, mapOf("categoryId" to 999_999, "name" to "X", "price" to 1))
            .isError(422, "INVALID_REFERENCE").andExpect(jsonPath("$.error.details[0].field").value("categoryId"))
        api.post("/api/v1/products", admin.accessToken, mapOf("categoryId" to drinks, "name" to "Âm", "price" to -1))
            .isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/products", admin.accessToken, mapOf("categoryId" to drinks, "name" to "", "price" to 1))
            .isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/products", admin.accessToken, mapOf("categoryId" to drinks, "name" to "Thiếu giá"))
            .isError(400, "VALIDATION_FAILED")
        api.postRaw("/api/v1/products", admin.accessToken, """{"categoryId":$drinks,"name":"Sai","price":1,"status":"DELETED"}""")
            .isError(400, "MALFORMED_REQUEST")
        assertEquals(13L, count("SELECT count(*) FROM product"))
    }

    @Test
    fun `STAFF and KITCHEN can read but not create or edit products`() {
        val pho = productId("Phở bò tái")
        listOf(staff, kitchen).forEach { user ->
            api.get("/api/v1/products?categoryId=${categoryId("Món chính")}", user.accessToken).andExpect(status().isOk)
            api.get("/api/v1/products/$pho", user.accessToken).andExpect(status().isOk)
            api.post("/api/v1/products", user.accessToken, mapOf("categoryId" to 1, "name" to "X", "price" to 1))
                .isError(403, "PERMISSION_DENIED")
            api.put(
                "/api/v1/products/$pho", user.accessToken,
                mapOf("categoryId" to 1, "name" to "X", "price" to 1, "displayOrder" to 1, "version" to 0),
            ).isError(403, "PERMISSION_DENIED")
            api.put("/api/v1/products/$pho/option-groups", user.accessToken, mapOf("optionGroupIds" to emptyList<Long>(), "version" to 0))
                .isError(403, "PERMISSION_DENIED")
        }
        assertEquals(65_000L, count("SELECT price FROM product WHERE id = ?", pho))
    }

    @Test
    fun `search and filter by category, status and keyword with paging, detail shows assigned option groups in order`() {
        val main = categoryId("Món chính")
        val page = data(api.get("/api/v1/products?categoryId=$main&size=2", kitchen.accessToken))
        assertEquals(4L, page["totalElements"].asLong())
        assertEquals(listOf("Phở bò tái", "Bún chả Hà Nội"), page["items"].map { it["name"].asText() })
        assertTrue(page["items"][0]["optionGroups"].isNull, "Danh sách không kèm nhóm option")

        val soldOutItems = data(api.get("/api/v1/products?status=SOLD_OUT", kitchen.accessToken))
        assertEquals(listOf("Mì xào hải sản"), soldOutItems["items"].map { it["name"].asText() })
        val hiddenItems = data(api.get("/api/v1/products?status=HIDDEN", admin.accessToken))
        assertEquals(listOf("Bánh flan"), hiddenItems["items"].map { it["name"].asText() })
        val keyword = data(api.get("/api/v1/products?q=trà", staff.accessToken))
        assertEquals(setOf("Trà đào cam sả", "Trà đá"), keyword["items"].map { it["name"].asText() }.toSet())

        val detail = data(api.get("/api/v1/products/${productId("Trà đào cam sả")}", staff.accessToken))
        assertEquals(listOf("Kích cỡ", "Mức đường", "Mức đá", "Topping"), detail["optionGroups"].map { it["name"].asText() })
        assertEquals(listOf(1, 2, 3, 4), detail["optionGroups"].map { it["displayOrder"].asInt() })
        api.get("/api/v1/products/999999", staff.accessToken).isError(404, "RESOURCE_NOT_FOUND")
    }

    @Test
    fun `price change is audited as PRODUCT_PRICE_CHANGED with old and new price, other fields as PRODUCT_UPDATED`() {
        val pho = productId("Phở bò tái")
        val main = categoryId("Món chính")
        val updated = data(
            api.put(
                "/api/v1/products/$pho", admin.accessToken,
                mapOf("categoryId" to main, "name" to "Phở bò tái", "description" to "Phở bò tái nạm", "price" to 70_000, "displayOrder" to 1, "version" to 0),
            ).andExpect(status().isOk),
        )
        assertEquals(70_000L, updated["price"].asLong())
        assertEquals(1L, updated["version"].asLong())
        val price = api.auditRows(MenuAuditActions.PRODUCT_PRICE_CHANGED).single()
        assertEquals(65_000L, api.jsonOf(price["old_value"])["price"].asLong())
        assertEquals(70_000L, api.jsonOf(price["new_value"])["price"].asLong())
        assertEquals(0, api.auditRows(MenuAuditActions.PRODUCT_UPDATED).size, "Chỉ đổi giá")
        assertEquals("PRICE_CHANGED", topic.messages.single()["change"].asText())
        assertEquals(70_000L, topic.messages.single()["price"].asLong())

        val starters = categoryId("Khai vị")
        api.put(
            "/api/v1/products/$pho", manager.accessToken,
            mapOf("categoryId" to starters, "name" to "Phở đặc biệt", "price" to 70_000, "displayOrder" to 3, "version" to 1),
        ).andExpect(status().isOk)
        val info = api.auditRows(MenuAuditActions.PRODUCT_UPDATED).single()
        assertEquals(setOf("categoryId", "name", "description", "displayOrder"), api.jsonOf(info["old_value"]).fieldNames().asSequence().toSet())
        assertEquals(1, api.auditRows(MenuAuditActions.PRODUCT_PRICE_CHANGED).size)
        assertEquals("UPDATED", topic.messages.last()["change"].asText())

        api.put(
            "/api/v1/products/$pho", admin.accessToken,
            mapOf("categoryId" to 999_999, "name" to "X", "price" to 1, "displayOrder" to 1, "version" to 2),
        ).isError(422, "INVALID_REFERENCE")
        api.put(
            "/api/v1/products/$pho", admin.accessToken,
            mapOf("categoryId" to starters, "name" to "Cũ", "price" to 1, "displayOrder" to 1, "version" to 0),
        ).isError(409, "CONCURRENT_MODIFICATION")
        assertEquals(70_000L, count("SELECT price FROM product WHERE id = ?", pho))
    }

    @Test
    fun `KITCHEN marks SOLD_OUT - ProductSoldOutEvent published inside the transaction, menu event after commit`() {
        val pho = productId("Phở bò tái")
        val result = data(
            api.patch("/api/v1/products/$pho/status", kitchen.accessToken, statusBody("SOLD_OUT", 0, "Hết bánh phở"))
                .andExpect(status().isOk),
        )
        assertEquals("SOLD_OUT", result["status"].asText())
        assertEquals(kitchen.id, result["statusChangedBy"].asLong())
        assertNotNull(result["statusChangedAt"].textValue())

        val recorded = soldOut.events.single()
        assertEquals(ProductSoldOutEvent(pho, kitchen.id), recorded.event)
        assertTrue(recorded.insideTransaction, "Phát trong transaction để module order (GĐ4) dùng BEFORE_COMMIT được")

        val audit = api.auditRows(MenuAuditActions.PRODUCT_STATUS_CHANGED).single()
        assertEquals("Hết bánh phở", audit["reason"])
        assertEquals("AVAILABLE", api.jsonOf(audit["old_value"])["status"].asText())
        val message = topic.messages.single()
        assertEquals("STATUS_CHANGED", message["change"].asText())
        assertEquals("SOLD_OUT", message["productStatus"].asText())

        // Bấm lặp: không đổi gì, không phát thêm sự kiện.
        api.patch("/api/v1/products/$pho/status", staff.accessToken, statusBody("SOLD_OUT", 1)).andExpect(status().isOk)
        assertEquals(1, soldOut.events.size)
        assertEquals(1, topic.messages.size)

        // STAFF mở bán lại: không phát ProductSoldOutEvent.
        api.patch("/api/v1/products/$pho/status", staff.accessToken, statusBody("AVAILABLE", 1)).andExpect(status().isOk)
        assertEquals(1, soldOut.events.size)
        assertEquals("AVAILABLE", topic.messages.last()["productStatus"].asText())
    }

    @Test
    fun `HIDDEN is only for ADMIN and MANAGER, failed status change publishes nothing`() {
        val pho = productId("Phở bò tái")
        api.patch("/api/v1/products/$pho/status", staff.accessToken, statusBody("HIDDEN", 0)).isError(403, "PERMISSION_DENIED")
        api.patch("/api/v1/products/$pho/status", kitchen.accessToken, statusBody("HIDDEN", 0)).isError(403, "PERMISSION_DENIED")
        api.patch("/api/v1/products/$pho/status", manager.accessToken, statusBody("HIDDEN", 0)).andExpect(status().isOk)

        // Bỏ ẩn (HIDDEN -> AVAILABLE/SOLD_OUT) cũng chỉ ADMIN/MANAGER.
        api.patch("/api/v1/products/$pho/status", kitchen.accessToken, statusBody("SOLD_OUT", 1)).isError(403, "PERMISSION_DENIED")
        api.patch("/api/v1/products/$pho/status", admin.accessToken, statusBody("SOLD_OUT", 0)).isError(409, "CONCURRENT_MODIFICATION")
        assertEquals("HIDDEN", jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String::class.java, pho))
        assertEquals(0, soldOut.events.size, "Transaction lỗi: không có ProductSoldOutEvent")
        assertEquals(1, topic.messages.size, "Chỉ lần ẩn thành công có sự kiện /topic/menu")

        api.patch("/api/v1/products/$pho/status", admin.accessToken, statusBody("SOLD_OUT", 1)).andExpect(status().isOk)
        assertEquals(1, soldOut.events.size)
        api.patch("/api/v1/products/$pho/status", null, statusBody("AVAILABLE", 2)).isError(401, "UNAUTHENTICATED")
    }

    @Test
    fun `assign, reorder and remove option groups in one request - product version increments`() {
        val pho = productId("Phở bò tái")
        val size = optionGroupId("Kích cỡ")
        val extra = optionGroupId("Thêm món")
        val topping = optionGroupId("Topping")

        val result = data(
            api.put("/api/v1/products/$pho/option-groups", admin.accessToken, mapOf("optionGroupIds" to listOf(topping, extra), "version" to 0))
                .andExpect(status().isOk),
        )
        assertEquals(listOf(topping, extra), result["optionGroups"].map { it["optionGroupId"].asLong() })
        assertEquals(listOf(1, 2), result["optionGroups"].map { it["displayOrder"].asInt() })
        assertEquals(1L, result["version"].asLong())
        assertEquals(1L, version("product", pho))
        assertEquals(0L, count("SELECT count(*) FROM product_option_group WHERE product_id = ? AND option_group_id = ?", pho, size))

        val audit = api.auditRows(MenuAuditActions.PRODUCT_OPTION_GROUPS_CHANGED).single()
        assertEquals(listOf(size, extra), api.jsonOf(audit["old_value"])["optionGroupIds"].map { it.asLong() })
        assertEquals(listOf(topping, extra), api.jsonOf(audit["new_value"])["optionGroupIds"].map { it.asLong() })
        assertEquals("OPTIONS_CHANGED", topic.messages.single()["change"].asText())
        assertEquals(1L, topic.messages.single()["version"].asLong())

        // Danh sách giống hệt: không đổi version, không audit.
        api.put("/api/v1/products/$pho/option-groups", admin.accessToken, mapOf("optionGroupIds" to listOf(topping, extra), "version" to 1))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.version").value(1))
        // Version cũ: 409, không đổi.
        api.put("/api/v1/products/$pho/option-groups", admin.accessToken, mapOf("optionGroupIds" to emptyList<Long>(), "version" to 0))
            .isError(409, "CONCURRENT_MODIFICATION")
        // Gỡ hết.
        api.put("/api/v1/products/$pho/option-groups", manager.accessToken, mapOf("optionGroupIds" to emptyList<Long>(), "version" to 1))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.optionGroups.length()").value(0))
        assertEquals(0L, count("SELECT count(*) FROM product_option_group WHERE product_id = ?", pho))
        assertEquals(2, api.auditRows(MenuAuditActions.PRODUCT_OPTION_GROUPS_CHANGED).size)
    }

    @Test
    fun `option group assignment rejects duplicates, unknown groups and newly assigned inactive groups`() {
        val pho = productId("Phở bò tái")
        val size = optionGroupId("Kích cỡ")
        val ice = optionGroupId("Mức đá")
        api.put("/api/v1/products/$pho/option-groups", admin.accessToken, mapOf("optionGroupIds" to listOf(size, size), "version" to 0))
            .isError(400, "VALIDATION_FAILED")
        api.put("/api/v1/products/$pho/option-groups", admin.accessToken, mapOf("optionGroupIds" to listOf(size, 999_999), "version" to 0))
            .isError(422, "INVALID_REFERENCE")

        tx.executeWithoutResult { jdbc.update("UPDATE option_group SET is_active = false WHERE id IN (?, ?)", size, ice) }
        api.put("/api/v1/products/$pho/option-groups", admin.accessToken, mapOf("optionGroupIds" to listOf(size, ice), "version" to 0))
            .isError(422, "BUSINESS_RULE_VIOLATION")
        // Nhóm đã gán trước đó (Kích cỡ) nay ngừng dùng vẫn được giữ khi sắp xếp lại.
        api.put("/api/v1/products/$pho/option-groups", admin.accessToken, mapOf("optionGroupIds" to listOf(optionGroupId("Thêm món"), size), "version" to 0))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.optionGroups[1].active").value(false))
        assertEquals(1, api.auditRows(MenuAuditActions.PRODUCT_OPTION_GROUPS_CHANGED).size, "Chỉ lần thành công được ghi audit")
    }

    @Test
    fun `database constraints of product - price, status, https image url, hard delete`() {
        val main = categoryId("Món chính")
        val pho = productId("Phở bò tái")
        expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            tx.executeWithoutResult { jdbc.update("INSERT INTO product (category_id, name, price) VALUES (?, 'Âm', -1)", main) }
        }.also { assertEquals("ck_product_price", it.constraint) }
        expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            tx.executeWithoutResult { jdbc.update("UPDATE product SET status = 'DELETED' WHERE id = ?", pho) }
        }.also { assertEquals("ck_product_status", it.constraint) }
        expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            tx.executeWithoutResult { jdbc.update("UPDATE product SET image_url = 'http://insecure/x.jpg' WHERE id = ?", pho) }
        }.also { assertEquals("ck_product_image_url", it.constraint) }
        expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            tx.executeWithoutResult { jdbc.update("INSERT INTO product (category_id, name, price) VALUES (?, '  ', 1)", main) }
        }.also { assertEquals("ck_product_name_not_blank", it.constraint) }
        expectDbError(ErrorCode.INVALID_REFERENCE) {
            tx.executeWithoutResult { jdbc.update("INSERT INTO product (category_id, name, price) VALUES (999999, 'X', 1)") }
        }
        expectDbError(ErrorCode.HARD_DELETE_FORBIDDEN) { tx.executeWithoutResult { jdbc.update("DELETE FROM product WHERE id = ?", pho) } }
        api.delete("/api/v1/products/$pho", admin.accessToken).isError(405, "METHOD_NOT_ALLOWED")
        assertEquals(12L, count("SELECT count(*) FROM product"))
    }
}
