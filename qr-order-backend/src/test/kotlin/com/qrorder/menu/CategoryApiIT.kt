package com.qrorder.menu

import com.qrorder.common.error.ErrorCode
import com.qrorder.menu.service.MenuAuditActions
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.MenuIntegrationTestBase
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * FR-CAT-01: CRUD danh mục, tìm kiếm, phân trang, sắp xếp display_order, ẩn/hiện, optimistic lock (409),
 * audit, phân quyền, /topic/menu sau commit, ràng buộc DB của bảng category.
 */
class CategoryApiIT : MenuIntegrationTestBase() {

    @Test
    fun `ADMIN creates a category - 201, audit and menu event after commit`() {
        val created = data(
            api.post(
                "/api/v1/categories", admin.accessToken,
                mapOf("name" to "  Lẩu  ", "description" to "Lẩu nấm", "displayOrder" to 5),
            ).andExpect(status().isCreated),
        )
        assertEquals("Lẩu", created["name"].asText(), "Tên được trim")
        assertEquals(5, created["displayOrder"].asInt())
        assertEquals(true, created["visible"].asBoolean(), "Mặc định hiển thị")
        assertEquals(0L, created["version"].asLong())

        val audit = api.auditRows(MenuAuditActions.CATEGORY_CREATED).single()
        assertEquals("category", audit["entity_type"])
        assertEquals(created["id"].asLong(), (audit["entity_id"] as Number).toLong())
        assertEquals(admin.id, (audit["actor_user_id"] as Number).toLong())
        assertEquals("Lẩu", api.jsonOf(audit["new_value"])["name"].asText())

        val event = topic.messages.single()
        assertEquals("MENU_CHANGED", event["type"].asText())
        assertEquals("CATEGORY", event["entityType"].asText())
        assertEquals("CREATED", event["change"].asText())
        assertEquals(created["id"].asLong(), event["entityId"].asLong())
    }

    @Test
    fun `MANAGER can manage categories, STAFF and KITCHEN can only read, anonymous gets 401`() {
        api.post("/api/v1/categories", manager.accessToken, mapOf("name" to "Món nướng")).andExpect(status().isCreated)
        listOf(staff, kitchen).forEach { user ->
            api.get("/api/v1/categories", user.accessToken).andExpect(status().isOk)
            api.get("/api/v1/categories/${categoryId("Món chính")}", user.accessToken).andExpect(status().isOk)
            api.post("/api/v1/categories", user.accessToken, mapOf("name" to "Không được")).isError(403, "PERMISSION_DENIED")
            api.patch(
                "/api/v1/categories/${categoryId("Món chính")}/visibility", user.accessToken,
                mapOf("visible" to false, "version" to 0),
            ).isError(403, "PERMISSION_DENIED")
        }
        api.get("/api/v1/categories", null).isError(401, "UNAUTHENTICATED")
        assertEquals(1L, count("SELECT count(*) FROM category WHERE name = 'Món nướng'"))
        assertEquals(0L, count("SELECT count(*) FROM category WHERE name = 'Không được'"))
    }

    @Test
    fun `duplicate name (case-insensitive) is rejected with 409 CATEGORY_NAME_TAKEN, validation errors with 400`() {
        // Chỉ khác hoa/thường ở ký tự ASCII để không phụ thuộc locale của PostgreSQL trong container.
        api.post("/api/v1/categories", admin.accessToken, mapOf("name" to "kHAI vị")).isError(409, "CATEGORY_NAME_TAKEN")
        api.post("/api/v1/categories", admin.accessToken, mapOf("name" to "   ")).isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details[0].field").value("name"))
        api.post("/api/v1/categories", admin.accessToken, mapOf("name" to "Âm", "displayOrder" to -1))
            .isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/categories", admin.accessToken, mapOf("name" to "x".repeat(101))).isError(400, "VALIDATION_FAILED")
        assertEquals(4L, count("SELECT count(*) FROM category"))

        // Đổi tên trùng danh mục khác cũng bị chặn; đổi hoa/thường của chính nó thì được.
        val id = categoryId("Khai vị")
        api.put("/api/v1/categories/$id", admin.accessToken, mapOf("name" to "tráng miệng", "displayOrder" to 2, "version" to 0))
            .isError(409, "CATEGORY_NAME_TAKEN")
        api.put("/api/v1/categories/$id", admin.accessToken, mapOf("name" to "KHAI VỊ", "displayOrder" to 2, "version" to 0))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.name").value("KHAI VỊ"))
    }

    @Test
    fun `search by keyword with paging sorted by displayOrder, filter by visible, LIKE wildcards are literal`() {
        createCategory("Món chay", displayOrder = 0)
        createCategory("50% giảm", displayOrder = 9)

        val all = data(api.get("/api/v1/categories?page=0&size=3", staff.accessToken).andExpect(status().isOk))
        assertEquals(6L, all["totalElements"].asLong())
        assertEquals(2, all["totalPages"].asInt())
        assertEquals(listOf("Món chay", "Món chính", "Khai vị"), all["items"].map { it["name"].asText() })

        val keyword = data(api.get("/api/v1/categories?q=món", staff.accessToken))
        assertEquals(setOf("Món chay", "Món chính"), keyword["items"].map { it["name"].asText() }.toSet())

        val percent = data(api.get("/api/v1/categories?q=%", staff.accessToken))
        assertEquals(listOf("50% giảm"), percent["items"].map { it["name"].asText() }, "% là ký tự thường, không phải ký tự đại diện")

        api.patch(
            "/api/v1/categories/${categoryId("Khai vị")}/visibility", admin.accessToken, mapOf("visible" to false, "version" to 0),
        ).andExpect(status().isOk)
        val hidden = data(api.get("/api/v1/categories?visible=false", staff.accessToken))
        assertEquals(listOf("Khai vị"), hidden["items"].map { it["name"].asText() })
    }

    @Test
    fun `update with current version succeeds, audits only changed fields and increments version`() {
        val id = categoryId("Khai vị")
        val updated = data(
            api.put(
                "/api/v1/categories/$id", admin.accessToken,
                mapOf("name" to "Khai vị", "description" to "Gỏi, nem", "displayOrder" to 7, "version" to 0),
            ).andExpect(status().isOk),
        )
        assertEquals(1L, updated["version"].asLong())
        assertEquals(7, updated["displayOrder"].asInt())

        val audit = api.auditRows(MenuAuditActions.CATEGORY_UPDATED).single()
        val old = api.jsonOf(audit["old_value"])
        val new = api.jsonOf(audit["new_value"])
        assertEquals(setOf("description", "displayOrder"), old.fieldNames().asSequence().toSet())
        assertEquals("Món ăn nhẹ", old["description"].asText())
        assertEquals(7, new["displayOrder"].asInt())
        assertEquals("UPDATED", topic.messages.single()["change"].asText())
        assertEquals(1L, topic.messages.single()["version"].asLong())

        // Gửi lại y hệt: không đổi gì, không audit, không sự kiện.
        api.put(
            "/api/v1/categories/$id", admin.accessToken,
            mapOf("name" to "Khai vị", "description" to "Gỏi, nem", "displayOrder" to 7, "version" to 1),
        ).andExpect(status().isOk).andExpect(jsonPath("$.data.version").value(1))
        assertEquals(1, api.auditRows(MenuAuditActions.CATEGORY_UPDATED).size)
        assertEquals(1, topic.messages.size)
    }

    @Test
    fun `stale version returns 409 CONCURRENT_MODIFICATION and changes nothing (no audit, no event)`() {
        val id = categoryId("Khai vị")
        api.put("/api/v1/categories/$id", admin.accessToken, mapOf("name" to "Khai vị 2", "displayOrder" to 2, "version" to 0))
            .andExpect(status().isOk)
        api.put("/api/v1/categories/$id", manager.accessToken, mapOf("name" to "Khai vị 3", "displayOrder" to 2, "version" to 0))
            .isError(409, "CONCURRENT_MODIFICATION")
        api.patch("/api/v1/categories/$id/visibility", manager.accessToken, mapOf("visible" to false, "version" to 0))
            .isError(409, "CONCURRENT_MODIFICATION")
        assertEquals("Khai vị 2", jdbc.queryForObject("SELECT name FROM category WHERE id = ?", String::class.java, id))
        assertEquals(true, jdbc.queryForObject("SELECT is_visible FROM category WHERE id = ?", Boolean::class.java, id))
        assertEquals(1, api.auditRows(MenuAuditActions.CATEGORY_UPDATED).size)
        assertEquals(1, topic.messages.size, "Chỉ lần sửa thành công có sự kiện")
    }

    @Test
    fun `concurrent updates with the same version - exactly one wins, the other gets 409 (@Version)`() {
        val id = categoryId("Tráng miệng")
        val tokens = listOf(admin.accessToken, manager.accessToken) // đăng nhập trước khi chạy song song
        repeat(3) { round ->
            val version = version("category", id)
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val results = (1..2).map { n ->
                    pool.submit(
                        Callable {
                            start.await()
                            val token = tokens[n - 1]
                            api.put(
                                "/api/v1/categories/$id", token,
                                mapOf("name" to "Tráng miệng $round-$n", "displayOrder" to 4, "version" to version),
                            ).andReturn().response.status
                        },
                    )
                }
                start.countDown()
                val statuses = results.map { it.get(30, TimeUnit.SECONDS) }.sorted()
                assertEquals(listOf(200, 409), statuses, "Vòng $round")
            } finally {
                pool.shutdownNow()
            }
            assertEquals(version + 1, version("category", id))
        }
    }

    @Test
    fun `hide and show a category - idempotent, audited, hidden category leaves the public menu`() {
        val id = categoryId("Tráng miệng")
        val hidden = data(
            api.patch("/api/v1/categories/$id/visibility", admin.accessToken, mapOf("visible" to false, "version" to 0))
                .andExpect(status().isOk),
        )
        assertEquals(false, hidden["visible"].asBoolean())
        api.patch("/api/v1/categories/$id/visibility", admin.accessToken, mapOf("visible" to false, "version" to 1))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.version").value(1))
        assertEquals(1, api.auditRows(MenuAuditActions.CATEGORY_VISIBILITY_CHANGED).size)
        assertEquals("VISIBILITY_CHANGED", topic.messages.single()["change"].asText())

        val menu = data(api.get("/api/v1/public/menu", null))
        assertTrue(menu["categories"].none { it["id"].asLong() == id })
        assertTrue(menu["categories"].flatMap { it["products"] }.none { it["name"].asText() == "Chè khúc bạch" })
    }

    @Test
    fun `reorder several categories atomically - stale version, duplicate or unknown id changes nothing`() {
        val main = categoryId("Món chính")
        val drinks = categoryId("Đồ uống")
        val result = data(
            api.put(
                "/api/v1/categories/display-order", admin.accessToken,
                mapOf("items" to listOf(mapOf("id" to drinks, "displayOrder" to 1, "version" to 0), mapOf("id" to main, "displayOrder" to 3, "version" to 0))),
            ).andExpect(status().isOk),
        )
        assertEquals(listOf(drinks, main), result.map { it["id"].asLong() })
        assertEquals(1, count("SELECT display_order FROM category WHERE id = ?", drinks).toInt())
        assertEquals(1, api.auditRows(MenuAuditActions.CATEGORIES_REORDERED).size)
        assertEquals("REORDERED", topic.messages.single()["change"].asText())
        assertTrue(topic.messages.single()["entityId"].isNull)

        val starter = categoryId("Khai vị")
        api.put(
            "/api/v1/categories/display-order", admin.accessToken,
            mapOf("items" to listOf(mapOf("id" to starter, "displayOrder" to 9, "version" to 0), mapOf("id" to main, "displayOrder" to 8, "version" to 0))),
        ).isError(409, "CONCURRENT_MODIFICATION")
        assertEquals(2L, count("SELECT display_order FROM category WHERE id = ?", starter), "Không đổi gì khi có một phần tử lệch version")

        api.put(
            "/api/v1/categories/display-order", admin.accessToken,
            mapOf("items" to listOf(mapOf("id" to starter, "displayOrder" to 9, "version" to 0), mapOf("id" to starter, "displayOrder" to 8, "version" to 0))),
        ).isError(400, "VALIDATION_FAILED")
        api.put(
            "/api/v1/categories/display-order", admin.accessToken,
            mapOf("items" to listOf(mapOf("id" to 999_999, "displayOrder" to 1, "version" to 0))),
        ).isError(404, "RESOURCE_NOT_FOUND")
        api.put("/api/v1/categories/display-order", admin.accessToken, mapOf("items" to emptyList<Any>()))
            .isError(400, "VALIDATION_FAILED")
    }

    @Test
    fun `no hard delete - API has no DELETE (405) and the database trigger blocks it`() {
        val id = categoryId("Khai vị")
        api.delete("/api/v1/categories/$id", admin.accessToken).isError(405, "METHOD_NOT_ALLOWED")
        expectDbError(ErrorCode.HARD_DELETE_FORBIDDEN) { tx.executeWithoutResult { jdbc.update("DELETE FROM category WHERE id = ?", id) } }
        api.get("/api/v1/categories/999999", admin.accessToken).isError(404, "RESOURCE_NOT_FOUND")
    }

    @Test
    fun `database constraints of category - blank name CHECK and case-insensitive unique index`() {
        expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            tx.executeWithoutResult { jdbc.update("INSERT INTO category (name) VALUES ('   ')") }
        }
        val translated = expectDbError(ErrorCode.CATEGORY_NAME_TAKEN) {
            tx.executeWithoutResult { jdbc.update("INSERT INTO category (name) VALUES ('KHAI vị')") }
        }
        assertEquals("ux_category_name_lower", translated.constraint)
        assertNull(api.auditRows(MenuAuditActions.CATEGORY_CREATED).firstOrNull())
    }
}
