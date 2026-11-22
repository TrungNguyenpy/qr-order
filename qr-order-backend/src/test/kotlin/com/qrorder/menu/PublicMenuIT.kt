package com.qrorder.menu

import com.fasterxml.jackson.databind.JsonNode
import com.qrorder.support.MenuIntegrationTestBase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * GET /api/v1/public/menu (Customer Web, SRS §3.2): không cần đăng nhập; danh mục hiển thị, món không HIDDEN
 * (SOLD_OUT có available = false), nhóm option đang dùng còn lựa chọn đang dùng, theo displayOrder.
 */
class PublicMenuIT : MenuIntegrationTestBase() {

    private fun menu(): JsonNode = data(api.get("/api/v1/public/menu", null).andExpect(status().isOk))

    private fun JsonNode.product(name: String): JsonNode? =
        this["categories"].flatMap { it["products"] }.firstOrNull { it["name"].asText() == name }

    @Test
    fun `anonymous client reads the seed menu in display order without hidden products or internal fields`() {
        val m = menu()
        assertNotNull(m["generatedAt"].textValue())
        assertEquals(listOf("Món chính", "Khai vị", "Đồ uống", "Tráng miệng"), m["categories"].map { it["name"].asText() })
        assertEquals(
            listOf("Phở bò tái", "Bún chả Hà Nội", "Cơm tấm sườn bì chả", "Mì xào hải sản"),
            m["categories"][0]["products"].map { it["name"].asText() },
        )
        assertEquals(null, m.product("Bánh flan"), "Món HIDDEN không hiển thị")

        val soldOut = m.product("Mì xào hải sản")!!
        assertEquals("SOLD_OUT", soldOut["status"].asText())
        assertFalse(soldOut["available"].asBoolean(), "SOLD_OUT hiển thị mờ, không đặt được")
        assertTrue(m.product("Trà đá")!!["available"].asBoolean())
        assertEquals(0L, m.product("Trà đá")!!["price"].asLong())

        val pho = m.product("Phở bò tái")!!
        assertFalse(pho.has("version"), "Không lộ trường nội bộ")
        assertFalse(pho.has("statusChangedBy"))
        assertEquals(listOf("Kích cỡ", "Thêm món"), pho["optionGroups"].map { it["name"].asText() })
        val size = pho["optionGroups"][0]
        assertEquals("SINGLE", size["selectionType"].asText())
        assertTrue(size["required"].asBoolean())
        assertEquals(1, size["minSelect"].asInt())
        assertEquals(1, size["maxSelect"].asInt())
        assertEquals(listOf("Nhỏ", "Vừa", "Lớn"), size["items"].map { it["name"].asText() })
        assertEquals(listOf(0L, 5_000L, 10_000L), size["items"].map { it["extraPrice"].asLong() })
        assertTrue(pho["optionGroups"][1]["maxSelect"].isNull, "Thêm món: không giới hạn")

        assertEquals(
            listOf("Kích cỡ", "Mức đường", "Mức đá", "Topping"),
            m.product("Trà đào cam sả")!!["optionGroups"].map { it["name"].asText() },
        )
    }

    @Test
    fun `inactive option items and groups, and groups without active items are not shown`() {
        val pho = productId("Phở bò tái")
        tx.executeWithoutResult {
            jdbc.update("UPDATE option_item SET is_active = false WHERE id = ?", optionItemId("Kích cỡ", "Lớn"))
            jdbc.update("UPDATE option_group SET is_active = false WHERE id = ?", optionGroupId("Topping"))
            jdbc.update("UPDATE option_item SET is_active = false WHERE option_group_id = ?", optionGroupId("Thêm món"))
        }
        val m = menu()
        val phoMenu = m.product("Phở bò tái")!!
        assertEquals(listOf("Kích cỡ"), phoMenu["optionGroups"].map { it["name"].asText() }, "Thêm món không còn lựa chọn nào")
        assertEquals(listOf("Nhỏ", "Vừa"), phoMenu["optionGroups"][0]["items"].map { it["name"].asText() })
        assertEquals(
            listOf("Kích cỡ", "Mức đường", "Mức đá"),
            m.product("Trà đào cam sả")!!["optionGroups"].map { it["name"].asText() },
        )
        assertEquals(pho, phoMenu["id"].asLong())
    }

    @Test
    fun `menu reflects admin changes - hidden category, new product order, per-product option group order`() {
        val dessert = categoryId("Tráng miệng")
        val main = categoryId("Món chính")
        createProduct(main, "Bò kho", price = 70_000, displayOrder = 0)
        api.patch("/api/v1/categories/$dessert/visibility", admin.accessToken, mapOf("visible" to false, "version" to 0))
            .andExpect(status().isOk)
        val juice = productId("Nước cam ép")
        api.put(
            "/api/v1/products/$juice/option-groups", admin.accessToken,
            mapOf("optionGroupIds" to listOf(optionGroupId("Mức đá"), optionGroupId("Mức đường")), "version" to 0),
        ).andExpect(status().isOk)

        val m = menu()
        assertEquals(listOf("Món chính", "Khai vị", "Đồ uống"), m["categories"].map { it["name"].asText() })
        assertEquals("Bò kho", m["categories"][0]["products"][0]["name"].asText())
        val groups = m.product("Nước cam ép")!!["optionGroups"]
        assertEquals(listOf("Mức đá", "Mức đường"), groups.map { it["name"].asText() })
        assertEquals(listOf(1, 2), groups.map { it["displayOrder"].asInt() })
    }

    @Test
    fun `staff token is not required but also not rejected on the public menu`() {
        api.get("/api/v1/public/menu", kitchen.accessToken).andExpect(status().isOk)
    }
}
