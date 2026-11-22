package com.qrorder.menu

import com.qrorder.common.error.ErrorCode
import com.qrorder.menu.service.MenuAuditActions
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.MenuIntegrationTestBase
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * FR-PRD-03 / BR-O03: nhóm option (SINGLE/MULTIPLE, required, minSelect, maxSelect) và lựa chọn (phụ thu >= 0,
 * ngừng dùng thay cho xóa, tên duy nhất trong nhóm), optimistic lock, phân quyền, ràng buộc DB.
 */
class OptionGroupApiIT : MenuIntegrationTestBase() {

    private fun createGroup(body: Map<String, Any?>, token: String = admin.accessToken) =
        api.post("/api/v1/option-groups", token, body)

    @Test
    fun `create groups - defaults derived from selection type and required flag`() {
        val single = data(createGroup(mapOf("name" to "Độ chín", "selectionType" to "SINGLE", "required" to true)).andExpect(status().isCreated))
        assertEquals(1, single["minSelect"].asInt())
        assertEquals(1, single["maxSelect"].asInt())
        assertEquals(true, single["active"].asBoolean())
        assertTrue(single["items"].isEmpty)

        val multi = data(createGroup(mapOf("name" to "Sốt", "selectionType" to "MULTIPLE", "required" to false), manager.accessToken))
        assertEquals(0, multi["minSelect"].asInt())
        assertTrue(multi["maxSelect"].isNull, "MULTIPLE bỏ trống maxSelect = không giới hạn")

        val bounded = data(createGroup(mapOf("name" to "Combo", "selectionType" to "MULTIPLE", "required" to true, "minSelect" to 2, "maxSelect" to 3)))
        assertEquals(2, bounded["minSelect"].asInt())
        assertEquals(3, bounded["maxSelect"].asInt())

        assertEquals(3, api.auditRows(MenuAuditActions.OPTION_GROUP_CREATED).size)
        assertEquals(listOf("OPTION_GROUP"), topic.messages.map { it["entityType"].asText() }.distinct())
    }

    @Test
    fun `invalid group rules are rejected with field details (BR-O03)`() {
        createGroup(mapOf("name" to "A", "selectionType" to "SINGLE", "required" to false, "maxSelect" to 2))
            .isError(400, "VALIDATION_FAILED").andExpect(jsonPath("$.error.details[*].field", hasItem("maxSelect")))
        createGroup(mapOf("name" to "B", "selectionType" to "MULTIPLE", "required" to true, "minSelect" to 0))
            .isError(400, "VALIDATION_FAILED").andExpect(jsonPath("$.error.details[*].field", hasItem("minSelect")))
        createGroup(mapOf("name" to "C", "selectionType" to "MULTIPLE", "required" to false, "minSelect" to 1))
            .isError(400, "VALIDATION_FAILED")
        createGroup(mapOf("name" to "D", "selectionType" to "MULTIPLE", "required" to true, "minSelect" to 3, "maxSelect" to 2))
            .isError(400, "VALIDATION_FAILED")
        createGroup(mapOf("name" to "E", "selectionType" to "MULTIPLE", "required" to false, "maxSelect" to 0))
            .isError(400, "VALIDATION_FAILED")
        createGroup(mapOf("name" to "F", "selectionType" to "MULTIPLE", "required" to false, "minSelect" to -1))
            .isError(400, "VALIDATION_FAILED")
        createGroup(mapOf("name" to " ", "selectionType" to "SINGLE", "required" to false)).isError(400, "VALIDATION_FAILED")
        createGroup(mapOf("name" to "G", "required" to false)).isError(400, "VALIDATION_FAILED")
        assertEquals(5L, count("SELECT count(*) FROM option_group"))
    }

    @Test
    fun `update group with version, deactivate and reactivate - stale version 409`() {
        val id = optionGroupId("Topping")
        val updated = data(
            api.put(
                "/api/v1/option-groups/$id", admin.accessToken,
                mapOf("name" to "Topping thêm", "selectionType" to "MULTIPLE", "required" to false, "maxSelect" to 2, "displayOrder" to 4, "version" to 0),
            ).andExpect(status().isOk),
        )
        assertEquals(2, updated["maxSelect"].asInt())
        assertEquals(1L, updated["version"].asLong())
        assertEquals(3, updated["items"].size(), "Trả kèm lựa chọn")
        val audit = api.auditRows(MenuAuditActions.OPTION_GROUP_UPDATED).single()
        assertEquals(setOf("name", "maxSelect"), api.jsonOf(audit["new_value"]).fieldNames().asSequence().toSet())

        api.put(
            "/api/v1/option-groups/$id", manager.accessToken,
            mapOf("name" to "Cũ", "selectionType" to "MULTIPLE", "required" to false, "displayOrder" to 4, "version" to 0),
        ).isError(409, "CONCURRENT_MODIFICATION")

        api.patch("/api/v1/option-groups/$id/active", admin.accessToken, mapOf("active" to false, "version" to 1))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.active").value(false))
        api.patch("/api/v1/option-groups/$id/active", admin.accessToken, mapOf("active" to false, "version" to 2))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.version").value(2))
        assertEquals(1, api.auditRows(MenuAuditActions.OPTION_GROUP_ACTIVE_CHANGED).size)

        val inactive = data(api.get("/api/v1/option-groups?active=false", staff.accessToken))
        assertEquals(listOf("Topping thêm"), inactive["items"].map { it["name"].asText() })
        api.get("/api/v1/option-groups/999999", staff.accessToken).isError(404, "RESOURCE_NOT_FOUND")
    }

    @Test
    fun `option items - create, update price, unique active name per group, deactivate and reactivate`() {
        val size = optionGroupId("Kích cỡ")
        val item = data(
            api.post("/api/v1/option-groups/$size/items", admin.accessToken, mapOf("name" to "Siêu lớn", "extraPrice" to 15_000, "displayOrder" to 4))
                .andExpect(status().isCreated),
        )
        assertEquals(size, item["optionGroupId"].asLong())
        assertEquals(15_000L, item["extraPrice"].asLong())
        val itemId = item["id"].asLong()

        // Trùng tên (không phân biệt hoa thường ở ký tự ASCII) với lựa chọn đang dùng trong cùng nhóm: 409.
        api.post("/api/v1/option-groups/$size/items", admin.accessToken, mapOf("name" to "siêu lớn")).isError(409, "OPTION_ITEM_NAME_TAKEN")
        // Cùng tên ở nhóm khác: được.
        api.post("/api/v1/option-groups/${optionGroupId("Topping")}/items", admin.accessToken, mapOf("name" to "Siêu lớn"))
            .andExpect(status().isCreated)

        api.put(
            "/api/v1/option-groups/$size/items/$itemId", manager.accessToken,
            mapOf("name" to "Siêu lớn", "extraPrice" to 20_000, "displayOrder" to 4, "version" to 0),
        ).andExpect(status().isOk).andExpect(jsonPath("$.data.extraPrice").value(20_000))
        val audit = api.auditRows(MenuAuditActions.OPTION_ITEM_UPDATED).single()
        assertEquals(15_000L, api.jsonOf(audit["old_value"])["extraPrice"].asLong())

        api.patch("/api/v1/option-groups/$size/items/$itemId/active", admin.accessToken, mapOf("active" to false, "version" to 1))
            .andExpect(status().isOk)
        // Lựa chọn cũ ngừng dùng -> tạo mới cùng tên được (unique chỉ áp cho lựa chọn đang dùng).
        val replacement = data(
            api.post("/api/v1/option-groups/$size/items", admin.accessToken, mapOf("name" to "Siêu lớn", "extraPrice" to 18_000, "displayOrder" to 5))
                .andExpect(status().isCreated),
        )
        // Dùng lại lựa chọn cũ khi đã có lựa chọn đang dùng trùng tên: 409.
        api.patch("/api/v1/option-groups/$size/items/$itemId/active", admin.accessToken, mapOf("active" to true, "version" to 2))
            .isError(409, "OPTION_ITEM_NAME_TAKEN")
        assertTrue(replacement["active"].asBoolean())

        val group = data(api.get("/api/v1/option-groups/$size", kitchen.accessToken))
        assertEquals(listOf("Nhỏ", "Vừa", "Lớn", "Siêu lớn", "Siêu lớn"), group["items"].map { it["name"].asText() })
        assertEquals(1, group["items"].count { !it["active"].asBoolean() })
    }

    @Test
    fun `option item validation, wrong group in URL and stale version`() {
        val size = optionGroupId("Kích cỡ")
        val topping = optionGroupId("Topping")
        val small = optionItemId("Kích cỡ", "Nhỏ")
        api.post("/api/v1/option-groups/$size/items", admin.accessToken, mapOf("name" to "Âm", "extraPrice" to -1)).isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/option-groups/$size/items", admin.accessToken, mapOf("name" to "")).isError(400, "VALIDATION_FAILED")
        api.post("/api/v1/option-groups/999999/items", admin.accessToken, mapOf("name" to "X")).isError(404, "RESOURCE_NOT_FOUND")
        // Lựa chọn thuộc nhóm khác với nhóm trên URL: 404.
        api.put(
            "/api/v1/option-groups/$topping/items/$small", admin.accessToken,
            mapOf("name" to "Nhỏ", "extraPrice" to 0, "displayOrder" to 1, "version" to 0),
        ).isError(404, "RESOURCE_NOT_FOUND")
        api.put(
            "/api/v1/option-groups/$size/items/$small", admin.accessToken,
            mapOf("name" to "Nhỏ", "extraPrice" to 1000, "displayOrder" to 1, "version" to 3),
        ).isError(409, "CONCURRENT_MODIFICATION")
        assertEquals(0L, count("SELECT extra_price FROM option_item WHERE id = ?", small))
    }

    @Test
    fun `STAFF and KITCHEN can read option groups but not change them`() {
        val size = optionGroupId("Kích cỡ")
        listOf(staff, kitchen).forEach { user ->
            api.get("/api/v1/option-groups", user.accessToken).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.totalElements").value(5))
            createGroup(mapOf("name" to "X", "selectionType" to "SINGLE", "required" to false), user.accessToken).isError(403, "PERMISSION_DENIED")
            api.post("/api/v1/option-groups/$size/items", user.accessToken, mapOf("name" to "X")).isError(403, "PERMISSION_DENIED")
            api.patch("/api/v1/option-groups/$size/active", user.accessToken, mapOf("active" to false, "version" to 0))
                .isError(403, "PERMISSION_DENIED")
        }
    }

    @Test
    fun `database constraints of option_group and option_item match the service rules`() {
        fun group(sql: String) = tx.executeWithoutResult { jdbc.update(sql) }
        expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            group("INSERT INTO option_group (name, selection_type, is_required, min_select, max_select) VALUES ('a', 'SINGLE', false, 0, 2)")
        }.also { assertEquals("ck_option_group_single", it.constraint) }
        expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            group("INSERT INTO option_group (name, selection_type, is_required, min_select) VALUES ('a', 'MULTIPLE', true, 0)")
        }.also { assertEquals("ck_option_group_required", it.constraint) }
        expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            group("INSERT INTO option_group (name, selection_type, is_required, min_select, max_select) VALUES ('a', 'MULTIPLE', true, 3, 2)")
        }.also { assertEquals("ck_option_group_max_select", it.constraint) }
        expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            group("INSERT INTO option_group (name, selection_type) VALUES ('a', 'ANY')")
        }.also { assertEquals("ck_option_group_selection_type", it.constraint) }

        val size = optionGroupId("Kích cỡ")
        expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            group("INSERT INTO option_item (option_group_id, name, extra_price) VALUES ($size, 'x', -1)")
        }.also { assertEquals("ck_option_item_extra_price", it.constraint) }
        expectDbError(ErrorCode.OPTION_ITEM_NAME_TAKEN) {
            group("INSERT INTO option_item (option_group_id, name) VALUES ($size, 'nhỏ')")
        }
        // Lựa chọn ngừng dùng không chiếm tên.
        group("INSERT INTO option_item (option_group_id, name, is_active) VALUES ($size, 'Nhỏ', false)")
        expectDbError(ErrorCode.HARD_DELETE_FORBIDDEN) { group("DELETE FROM option_item WHERE option_group_id = $size") }
        expectDbError(ErrorCode.HARD_DELETE_FORBIDDEN) { group("DELETE FROM option_group WHERE id = $size") }
    }
}
