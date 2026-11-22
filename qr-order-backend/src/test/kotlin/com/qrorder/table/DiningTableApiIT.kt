package com.qrorder.table

import com.qrorder.common.error.ErrorCode
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.TableIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FR-TBL-01..03, DEC-04 B, BR-S08, BR-A03: bàn ăn qua API (COMMIT thật). */
class DiningTableApiIT : TableIntegrationTestBase() {

    private fun createTable(code: String, area: String? = "Khu A", capacity: Int = 4, order: Int = 1) =
        data(
            api.post("/api/v1/tables", admin.accessToken, mapOf("code" to code, "displayName" to "Bàn $code", "area" to area, "capacity" to capacity, "displayOrder" to order))
                .andExpect(status().isCreated),
        )

    @Test
    fun `admin creates a table - defaults, audit and table event after commit`() {
        val t = createTable("A01")
        assertEquals("AVAILABLE", t["status"].asText())
        assertTrue(t["active"].asBoolean())
        assertEquals(0L, t["version"].asLong())
        val audit = api.auditRows("TABLE_CREATED").single()
        assertEquals(admin.id, (audit["actor_user_id"] as Number).toLong())
        assertEquals("dining_table", audit["entity_type"])
        assertTrue((audit["new_value"] as String).contains("\"code\": \"A01\""))
        val events = stomp.on("/topic/tables")
        assertEquals(1, events.size)
        assertEquals("CREATED", events.single()["change"].asText())
        assertEquals("TABLE_CHANGED", events.single()["type"].asText())
    }

    @Test
    fun `validation, case-insensitive unique code and role checks`() {
        createTable("B01")
        api.post("/api/v1/tables", admin.accessToken, mapOf("code" to "b01", "displayName" to "x", "capacity" to 2))
            .isError(409, ErrorCode.TABLE_CODE_TAKEN.name)
        api.post("/api/v1/tables", admin.accessToken, mapOf("code" to "bad code!", "displayName" to " ", "capacity" to 101))
            .isError(400, "VALIDATION_FAILED")
            .andExpect(jsonPath("$.error.details.length()").value(3))
        for (user in listOf(manager, staff, kitchen)) {
            api.post("/api/v1/tables", user.accessToken, mapOf("code" to "C01", "displayName" to "x", "capacity" to 2)).isError(403, "PERMISSION_DENIED")
        }
        api.get("/api/v1/tables", kitchen.accessToken).isError(403, "PERMISSION_DENIED")
        api.get("/api/v1/tables", null).isError(401, "UNAUTHENTICATED")
        api.get("/api/v1/tables/999999", staff.accessToken).isError(404, "RESOURCE_NOT_FOUND")
        // CHECK của DB là hàng rào cuối (BR-S08, ck_dining_table_*).
        val check = expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            jdbc.update("INSERT INTO dining_table (code, display_name, capacity) VALUES ('X 1', 'x', 2)")
        }
        assertEquals("ck_dining_table_code", check.constraint)
    }

    @Test
    fun `list filters by keyword, area, status, active and floor plan shows active tables with sessions`() {
        createTable("F01", area = "Rooftop", order = 1)
        createTable("F02", area = "Rooftop", order = 2)
        val list = data(api.get("/api/v1/tables?area=rooftop&size=50", staff.accessToken).andExpect(status().isOk))
        assertEquals(listOf("F01", "F02"), list["items"].map { it["code"].asText() })
        assertEquals(1L, data(api.get("/api/v1/tables?q=f02", staff.accessToken))["totalElements"].asLong())
        assertEquals(1L, data(api.get("/api/v1/tables?active=false", staff.accessToken))["totalElements"].asLong()) // T99
        assertEquals(0L, data(api.get("/api/v1/tables?status=CLEANING", staff.accessToken))["totalElements"].asLong())
        assertEquals(0L, data(api.get("/api/v1/tables?q=%25", staff.accessToken))["totalElements"].asLong())

        val sessionId = joinOk("dev-qr-T01")["session"]["sessionId"].asLong()
        val plan = data(api.get("/api/v1/tables/floor-plan", manager.accessToken).andExpect(status().isOk))
        val codes = plan.map { it["table"]["code"].asText() }
        assertFalse("T99" in codes, "Bàn ngừng dùng không lên sơ đồ")
        val t01 = plan.first { it["table"]["code"].asText() == "T01" }
        assertEquals("OCCUPIED", t01["table"]["status"].asText())
        assertEquals(sessionId, t01["activeSession"]["id"].asLong())
        assertEquals(1L, t01["activeSession"]["deviceCount"].asLong())
        assertTrue(t01["hasActiveQr"].asBoolean())
        assertTrue(plan.first { it["table"]["code"].asText() == "F01" }["activeSession"].isNull)
    }

    @Test
    fun `update with version - audit only changed fields, stale version 409, identical request is a no-op`() {
        val id = createTable("U01")["id"].asLong()
        val body = mapOf("code" to "U01", "displayName" to "Bàn VIP", "area" to "Khu A", "capacity" to 8, "displayOrder" to 1, "version" to 0)
        val updated = data(api.put("/api/v1/tables/$id", admin.accessToken, body).andExpect(status().isOk))
        assertEquals(1L, updated["version"].asLong())
        assertEquals(8, updated["capacity"].asInt())
        val audit = api.auditRows("TABLE_UPDATED").single()
        val newValue = api.jsonOf(audit["new_value"])
        assertEquals(setOf("displayName", "capacity"), newValue.fieldNames().asSequence().toSet())
        api.put("/api/v1/tables/$id", admin.accessToken, body).isError(409, "CONCURRENT_MODIFICATION")
        api.put("/api/v1/tables/$id", admin.accessToken, body + ("version" to 1)).andExpect(status().isOk)
        assertEquals(1, api.auditRows("TABLE_UPDATED").size, "Gửi lại y hệt không ghi audit")
        api.put("/api/v1/tables/$id", admin.accessToken, body + mapOf("version" to 1, "code" to "T02")).isError(409, "TABLE_CODE_TAKEN")
    }

    @Test
    fun `two concurrent updates with the same version - exactly one wins`() {
        repeat(3) { round ->
            val id = createTable("R$round")["id"].asLong()
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            val results = (1..2).map { n ->
                pool.submit<Int> {
                    start.await()
                    api.put(
                        "/api/v1/tables/$id", admin.accessToken,
                        mapOf("code" to "R$round", "displayName" to "Tên $n", "capacity" to 4 + n, "displayOrder" to 1, "version" to 0),
                    ).andReturn().response.status
                }
            }
            start.countDown()
            val statuses = results.map { it.get(30, TimeUnit.SECONDS) }.sorted()
            pool.shutdown()
            assertEquals(listOf(200, 409), statuses, "Vòng $round")
        }
    }

    @Test
    fun `deactivate only when table is available and has no active session - BR-S08`() {
        val t01 = tableId("T01")
        joinOk("dev-qr-T01")
        api.patch("/api/v1/tables/$t01/active", admin.accessToken, mapOf("active" to false, "version" to version("dining_table", t01)))
            .isError(409, "TABLE_IN_USE")
        val t02 = tableId("T02")
        val off = data(api.patch("/api/v1/tables/$t02/active", admin.accessToken, mapOf("active" to false, "version" to 0)).andExpect(status().isOk))
        assertFalse(off["active"].asBoolean())
        api.patch("/api/v1/tables/$t02/active", staff.accessToken, mapOf("active" to true, "version" to 1)).isError(403, "PERMISSION_DENIED")
        join("dev-qr-T02").isError(409, "TABLE_INACTIVE")
        // Gửi lại đúng giá trị đang có: không đổi version, không audit.
        api.patch("/api/v1/tables/$t02/active", admin.accessToken, mapOf("active" to false, "version" to 1)).andExpect(jsonPath("$.data.version").value(1))
        assertEquals(1, api.auditRows("TABLE_ACTIVE_CHANGED").size)
        api.patch("/api/v1/tables/$t02/active", admin.accessToken, mapOf("active" to true, "version" to 1)).andExpect(jsonPath("$.data.active").value(true))
        // Hàng rào cuối ở DB: ck_dining_table_inactive_only_available.
        val check = expectDbError(ErrorCode.BUSINESS_RULE_VIOLATION) {
            jdbc.update("UPDATE dining_table SET is_active = false WHERE id = ?", t01)
        }
        assertEquals("ck_dining_table_inactive_only_available", check.constraint)
    }

    @Test
    fun `cleaning to available is the only manual status change and is for floor staff - FR-TBL-03`() {
        val t03 = tableId("T03")
        api.patch("/api/v1/tables/$t03/status", staff.accessToken, mapOf("status" to "CLEANING", "version" to 0))
            .isError(409, "INVALID_TABLE_TRANSITION")
        jdbc.update("UPDATE dining_table SET status = 'CLEANING' WHERE id = ?", t03)
        val v = version("dining_table", t03)
        api.patch("/api/v1/tables/$t03/status", admin.accessToken, mapOf("status" to "AVAILABLE", "version" to v)).isError(403, "PERMISSION_DENIED")
        api.patch("/api/v1/tables/$t03/status", kitchen.accessToken, mapOf("status" to "AVAILABLE", "version" to v)).isError(403, "PERMISSION_DENIED")
        api.patch("/api/v1/tables/$t03/status", staff.accessToken, mapOf("status" to "OCCUPIED", "version" to v)).isError(409, "INVALID_TABLE_TRANSITION")
        val ok = data(api.patch("/api/v1/tables/$t03/status", staff.accessToken, mapOf("status" to "AVAILABLE", "version" to v)).andExpect(status().isOk))
        assertEquals("AVAILABLE", ok["status"].asText())
        assertEquals(v + 1, ok["version"].asLong())
        val audit = api.auditRows("TABLE_STATUS_CHANGED").single()
        assertEquals(staff.id, (audit["actor_user_id"] as Number).toLong())
        assertTrue(stomp.on("/topic/tables").any { it["tableId"].asLong() == t03 && it["status"].asText() == "AVAILABLE" })
    }

    @Test
    fun `tables are never hard-deleted - BR-A03`() {
        val id = createTable("D01")["id"].asLong()
        api.delete("/api/v1/tables/$id", admin.accessToken).isError(405, "METHOD_NOT_ALLOWED")
        expectDbError(ErrorCode.HARD_DELETE_FORBIDDEN) { jdbc.update("DELETE FROM dining_table WHERE id = ?", id) }
        assertNull(stomp.on("/topic/tables").firstOrNull { it["change"].asText() == "DELETED" })
    }
}
