package com.qrorder.report

import com.qrorder.report.domain.ReportGroupBy
import com.qrorder.report.domain.ReportRange
import com.qrorder.report.domain.ReportWindows
import com.qrorder.report.repository.ReportRepository
import com.qrorder.report.repository.SQL_PAYMENTS_BY_METHOD
import com.qrorder.report.repository.SQL_PAYMENT_BILLS_BY_WINDOW
import com.qrorder.report.repository.SQL_REFUNDS_BY_METHOD
import com.qrorder.report.repository.SQL_WRITE_OFFS_BY_WINDOW
import com.qrorder.support.ApiTestClient.Companion.isError
import com.qrorder.support.ReportIntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GĐ6 - tổng hợp vận hành, món bán chạy, thời gian phục vụ, xuất CSV và kế hoạch truy vấn (FR-RPT-01..03,
 * BE-36, BE-38). Số liệu dựng bằng API thật; mốc thời gian được lùi bằng SQL để kiểm tra chính xác.
 */
class ReportOperationsIT : ReportIntegrationTestBase() {

    private fun get(path: String, token: String? = manager.accessToken) = api.get("/api/v1/reports$path", token)

    private fun staffPlace(sessionId: Long, vararg lines: Map<String, Any?>, token: String = staff.accessToken) =
        api.post(
            "/api/v1/orders/sessions/$sessionId/batches", token,
            mapOf("clientRequestId" to UUID.randomUUID().toString(), "items" to lines.toList()),
        )

    // ================================================================================================ Summary

    @Test
    fun `summary counts items batches sessions cancel and reject rates and wasted value`() {
        val (_, _, items1) = tableWithStandardOrder("T01")
        val (guest2, _, _) = tableWithStandardOrder("T02")
        staffPlace(guest2.sessionId, phoSmall()).andExpect(status().isCreated)
        advance(items1[0], "PREPARING")
        api.post("/api/v1/kitchen/items/${items1[0]}/reject", kitchen.accessToken, mapOf("reasonCode" to "OUT_OF_STOCK")).andExpect(status().isOk)
        api.post("/api/v1/orders/items/${items1[1]}/cancel", staff.accessToken, mapOf("reason" to "Khách đổi ý")).andExpect(status().isOk)

        val s = data(get("/summary").andExpect(status().isOk))

        assertEquals(todayVn(), s["fromDate"].asText())
        assertEquals(2, s["sessionsOpened"].asInt())
        assertEquals(3, s["batchesSubmitted"].asInt())
        assertEquals(1, s["batchesByStaff"].asInt())
        val items = s["items"]
        assertEquals(5, items["totalItems"].asInt())
        assertEquals(1, items["cancelledItems"].asInt())
        assertEquals(1, items["rejectedItems"].asInt())
        assertEquals(0.2, items["cancelRate"].asDouble(), 0.00001)
        assertEquals(0.2, items["rejectRate"].asDouble(), 0.00001)
        assertEquals(1, items["wastedItems"].asInt(), "món PREPARING bị bếp từ chối là wasted (BR-O06)")
        assertEquals(130_000L, items["wastedValue"].asLong())
        assertEquals(listOf("OUT_OF_STOCK" to 1, "STAFF_CANCELLED" to 1), items["byReason"].map { it["reasonCode"].asText() to it["count"].asInt() })
        assertEquals(0, s["writtenOffCount"].asInt())
        assertEquals(0, s["refundCount"].asInt())
    }

    @Test
    fun `summary of a range without data is all zero`() {
        tableWithStandardOrder("T01")

        val s = data(get("/summary?from=2026-01-01&to=2026-01-02").andExpect(status().isOk))

        assertEquals(0, s["sessionsOpened"].asInt())
        assertEquals(0, s["batchesSubmitted"].asInt())
        assertEquals(0, s["items"]["totalItems"].asInt())
        assertEquals(0.0, s["items"]["cancelRate"].asDouble(), 0.00001)
        assertEquals(0, s["items"]["byReason"].size())
    }

    @Test
    fun `summary shows refunds and write-offs of the range and the current attention snapshot`() {
        val (_, orderId, _) = tableWithStandardOrder("T03")
        val unpaidBill = createBillOk(orderId)["id"].asLong()
        requestVoidOk(unpaidBill)
        val (_, _, voidPendingBill) = paidAndClosed("T04")
        requestVoidOk(voidPendingBill)
        val (guest5, orderId5, _) = tableWithStandardOrder("T05")
        val bill5 = createBillOk(orderId5)["id"].asLong()
        payOk(bill5, 59_000)
        api.post("/api/v1/sessions/${guest5.sessionId}/force-close", manager.accessToken, mapOf("reason" to "Khách bỏ về")).andExpect(status().isOk)
        val (_, _, refundedBill) = paidAndClosed("T06")
        requestVoidOk(refundedBill)
        refundOk(refundedBill, 159_000)

        val s = data(get("/summary").andExpect(status().isOk))

        assertEquals(1, s["writtenOffCount"].asInt())
        assertEquals(100_000L, s["writtenOffShortfall"].asLong())
        assertEquals(1, s["refundCount"].asInt())
        assertEquals(159_000L, s["refundedAmount"].asLong())
        assertEquals(count("SELECT count(*) FROM orders WHERE needs_attention"), s["ordersNeedingAttention"].asLong())
        assertTrue(s["ordersNeedingAttention"].asInt() >= 1)
        assertEquals(count("SELECT count(*) FROM bill WHERE status = 'VOID_PENDING'"), s["billsVoidPending"].asLong())
        assertEquals(1, s["billsVoidPending"].asInt())
    }

    // ================================================================================================ Top products

    @Test
    fun `top products use billable items of PAID orders by payment date and rank by quantity or revenue`() {
        val (order1, _) = paidCustomOrder("T01", phoQty = 2, coffeeQty = 1)
        val (order2, _) = paidCustomOrder("T02", phoQty = 1, coffeeQty = 4)
        setOrderPaidAt(order1, "2026-11-12T12:00:00+07:00")
        setOrderPaidAt(order2, "2026-11-10T12:00:00+07:00")
        val unpaid = guestAt("T03")
        orderConfirmed(unpaid, coffeeSmall(5))

        val byQuantity = data(get("/top-products?from=2026-11-10&to=2026-11-12").andExpect(status().isOk))
        assertEquals("QUANTITY", byQuantity["sortBy"].asText())
        assertEquals(10, byQuantity["limit"].asInt())
        assertEquals(listOf(coffee, pho), byQuantity["products"].map { it["productName"].asText() })
        val coffeeRow = byQuantity["products"][0]
        assertEquals(1, coffeeRow["rank"].asInt())
        assertEquals(5L, coffeeRow["quantity"].asLong(), "T03 chưa thanh toán nên không tính")
        assertEquals(145_000L, coffeeRow["sales"].asLong())
        assertEquals(2, coffeeRow["orderCount"].asInt())
        assertEquals(3L, byQuantity["products"][1]["quantity"].asLong())
        assertEquals(195_000L, byQuantity["products"][1]["sales"].asLong())

        val byRevenue = data(get("/top-products?from=2026-11-10&to=2026-11-12&sortBy=REVENUE").andExpect(status().isOk))
        assertEquals(listOf(pho, coffee), byRevenue["products"].map { it["productName"].asText() })

        val limited = data(get("/top-products?from=2026-11-10&to=2026-11-12&limit=1").andExpect(status().isOk))
        assertEquals(1, limited["products"].size())

        val coffeeCategory = jdbc.queryForObject("SELECT category_id FROM product WHERE id = ?", Long::class.java, productId(coffee))!!
        val filtered = data(get("/top-products?from=2026-11-10&to=2026-11-12&categoryId=$coffeeCategory").andExpect(status().isOk))
        assertEquals(listOf(coffee), filtered["products"].map { it["productName"].asText() })
        assertEquals(coffeeCategory, filtered["categoryId"].asLong())

        val dayTwo = data(get("/top-products?from=2026-11-10&to=2026-11-10").andExpect(status().isOk))
        assertEquals(4L, dayTwo["products"].single { it["productName"].asText() == coffee }["quantity"].asLong())
        assertEquals(1L, dayTwo["products"].single { it["productName"].asText() == pho }["quantity"].asLong())
    }

    @Test
    fun `top products leave out cancelled items and unpaid orders`() {
        val guest = guestAt("T01")
        val items = orderConfirmed(guest, phoSmall(2), coffeeSmall())
        api.post("/api/v1/orders/items/${items[1]}/cancel", staff.accessToken, mapOf("reason" to "Hết cà phê")).andExpect(status().isOk)
        items.take(1).forEach { advance(it, "SERVED") }
        val orderId = orderIdOf(guest.sessionId)
        val bill = createBillOk(orderId)
        payOk(bill["id"].asLong(), bill["totalAmount"].asLong())
        setOrderPaidAt(orderId, "2026-11-12T12:00:00+07:00")

        val top = data(get("/top-products?from=2026-11-12&to=2026-11-12").andExpect(status().isOk))

        assertEquals(listOf(pho), top["products"].map { it["productName"].asText() })
        assertEquals(130_000L, top["products"][0]["sales"].asLong())
    }

    @Test
    fun `top products validate limit and sort parameters`() {
        get("/top-products?limit=0").isError(400, "VALIDATION_FAILED")
        get("/top-products?limit=101").isError(400, "VALIDATION_FAILED")
        get("/top-products?sortBy=PROFIT").isError(400, "MALFORMED_REQUEST")
        get("/top-products?limit=100").andExpect(status().isOk)
    }

    // ================================================================================================ Service time

    @Test
    fun `service time reports average and median per segment and skips confirmation for auto confirmed batches`() {
        val guest = guestAt("T01")
        val first = orderConfirmed(guest, phoSmall(), coffeeSmall())
        val second = orderConfirmed(guest, phoSmall())
        val (a, b) = first
        val c = second.single()
        listOf(a, b, c).forEach { advance(it, "SERVED") }
        val batches = jdbc.queryForList("SELECT id, requires_confirmation FROM order_batch ORDER BY seq")
        assertEquals(listOf(true, false), batches.map { it["requires_confirmation"] as Boolean })
        val base = at("2026-11-18T12:00:00+07:00")
        backdate {
            jdbc.update("UPDATE order_batch SET submitted_at = ? WHERE id = ?", base, (batches[0]["id"] as Number).toLong())
            jdbc.update("UPDATE order_batch SET submitted_at = ? WHERE id = ?", base.plusSeconds(1000), (batches[1]["id"] as Number).toLong())
            fun times(id: Long, confirmed: Long, preparing: Long, ready: Long, served: Long) = jdbc.update(
                "UPDATE order_item SET confirmed_at = ?, preparing_at = ?, ready_at = ?, served_at = ? WHERE id = ?",
                base.plusSeconds(confirmed), base.plusSeconds(preparing), base.plusSeconds(ready), base.plusSeconds(served), id,
            )
            times(a, 20, 30, 330, 360)
            times(b, 40, 70, 670, 700)
            times(c, 1000, 1110, 1410, 1500)
        }

        val st = data(get("/service-time?from=2026-11-18&to=2026-11-18").andExpect(status().isOk))

        assertEquals(3, st["servedItems"].asInt())
        assertEquals(listOf("CONFIRMATION", "KITCHEN_QUEUE", "COOKING", "DELIVERY", "TOTAL"), st["segments"].map { it["segment"].asText() })
        fun seg(name: String) = st["segments"].single { it["segment"].asText() == name }
        assertEquals(2, seg("CONFIRMATION")["sampleCount"].asInt())
        assertEquals(30.0, seg("CONFIRMATION")["averageSeconds"].asDouble(), 0.05)
        assertEquals(30.0, seg("CONFIRMATION")["medianSeconds"].asDouble(), 0.05)
        assertEquals(3, seg("KITCHEN_QUEUE")["sampleCount"].asInt())
        assertEquals(50.0, seg("KITCHEN_QUEUE")["averageSeconds"].asDouble(), 0.05)
        assertEquals(30.0, seg("KITCHEN_QUEUE")["medianSeconds"].asDouble(), 0.05)
        assertEquals(400.0, seg("COOKING")["averageSeconds"].asDouble(), 0.05)
        assertEquals(300.0, seg("COOKING")["medianSeconds"].asDouble(), 0.05)
        assertEquals(50.0, seg("DELIVERY")["averageSeconds"].asDouble(), 0.05)
        assertEquals(30.0, seg("DELIVERY")["medianSeconds"].asDouble(), 0.05)
        assertEquals(520.0, seg("TOTAL")["averageSeconds"].asDouble(), 0.05)
        assertEquals(500.0, seg("TOTAL")["medianSeconds"].asDouble(), 0.05)
    }

    @Test
    fun `service time without samples has null statistics`() {
        val st = data(get("/service-time?from=2026-01-01&to=2026-01-02").andExpect(status().isOk))

        assertEquals(0, st["servedItems"].asInt())
        st["segments"].forEach {
            assertEquals(0, it["sampleCount"].asInt())
            assertTrue(it["averageSeconds"].isNull)
            assertTrue(it["medianSeconds"].isNull)
        }
    }

    // ================================================================================================ CSV

    @Test
    fun `revenue csv is UTF-8 with BOM, downloadable and audited`() {
        setPaymentConfirmedAt(paidOrder("T01").third, "2026-11-20T10:00:00+07:00")

        val response = get("/revenue.csv?from=2026-11-20&to=2026-11-21").andExpect(status().isOk).andReturn().response

        val bytes = response.contentAsByteArray
        assertEquals(listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.take(3))
        assertTrue(response.contentType!!.startsWith("text/csv"), response.contentType)
        val disposition = response.getHeader("Content-Disposition")!!
        assertTrue(disposition.startsWith("attachment") && disposition.contains("revenue_2026-11-20_2026-11-21_DAY.csv"), disposition)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val lines = String(bytes, 3, bytes.size - 3, Charsets.UTF_8).split("\r\n").filter { it.isNotEmpty() }
        assertTrue(lines[0].startsWith("Kỳ,Ngày,Ca,Từ,Đến,Doanh thu gộp,Hoàn tiền,Doanh thu ròng"), lines[0])
        assertTrue(lines[0].endsWith("Thu CASH,Thu BANK_TRANSFER,Thu CARD_POS,Thu OTHER"), lines[0])
        assertEquals(4, lines.size, "tiêu đề, 2 ngày và dòng TỔNG")
        assertTrue(lines[1].startsWith("2026-11-20,2026-11-20,,2026-11-20T00:00+07:00,2026-11-21T00:00+07:00,159000,0,159000,1,1,0,0,0,159000,0,0,0"), lines[1])
        assertTrue(lines[2].startsWith("2026-11-21,2026-11-21,,") && lines[2].contains(",0,0,0,0,0,0,0,0,0,0,0,0"), lines[2])
        assertTrue(lines[3].startsWith("TỔNG,,,"), lines[3])

        val audit = api.auditRows("REPORT_EXPORTED").single()
        assertEquals(manager.id, (audit["actor_user_id"] as Number).toLong())
        assertEquals("report", audit["entity_type"])
        val detail = api.jsonOf(audit["new_value"])
        assertEquals("revenue", detail["report"].asText())
        assertEquals("CSV", detail["format"].asText())
        assertEquals("DAY", detail["groupBy"].asText())
    }

    @Test
    fun `top products csv has the product rows and is audited`() {
        val (order, _) = paidCustomOrder("T01", phoQty = 2, coffeeQty = 1)
        setOrderPaidAt(order, "2026-11-12T12:00:00+07:00")

        val response = get("/top-products.csv?from=2026-11-12&to=2026-11-12").andExpect(status().isOk).andReturn().response

        val lines = String(response.contentAsByteArray, 3, response.contentAsByteArray.size - 3, Charsets.UTF_8).split("\r\n").filter { it.isNotEmpty() }
        assertEquals("Hạng,Mã món,Tên món,Mã danh mục,Danh mục,Số lượng,Doanh số,Số Order", lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines.any { it.startsWith("1,") && it.contains("Phở bò tái") && it.contains(",2,130000,1") }, lines.toString())
        assertTrue(response.getHeader("Content-Disposition")!!.contains("top-products_2026-11-12_2026-11-12.csv"))
        assertEquals("top-products", api.jsonOf(api.auditRows("REPORT_EXPORTED").single()["new_value"])["report"].asText())
    }

    @Test
    fun `csv export needs REPORT_VIEW and a refused export leaves no audit row`() {
        get("/revenue.csv", staff.accessToken).isError(403, "PERMISSION_DENIED")
        get("/top-products.csv", null).isError(401, "UNAUTHENTICATED")
        get("/revenue.csv?from=2026-11-20", manager.accessToken).isError(400, "INVALID_REPORT_RANGE")

        assertEquals(0, api.auditRows("REPORT_EXPORTED").size)
    }

    @Test
    fun `summary, top products and service time also need REPORT_VIEW`() {
        val admin = adminWithReportView()
        listOf("/summary", "/top-products", "/service-time").forEach {
            get(it, staff.accessToken).isError(403, "PERMISSION_DENIED")
            get(it, kitchen.accessToken).isError(403, "PERMISSION_DENIED")
            get(it, null).isError(401, "UNAUTHENTICATED")
            get(it, admin.accessToken).andExpect(status().isOk)
        }
    }

    // ================================================================================================ Kế hoạch truy vấn

    @Test
    fun `revenue refund and write-off queries are served by the report indexes`() {
        val range = ReportRange.resolve("2026-11-01", "2026-11-30", LocalDate.of(2026, 11, 22), 366)
        val windows = ReportWindows.build(range, ReportGroupBy.DAY, emptyList(), null)
        val json = ReportRepository.windowsJson(windows).replace("'", "''")

        fun plan(sql: String): String = tx.execute {
            jdbc.execute("SET LOCAL enable_seqscan = off")
            jdbc.queryForList("EXPLAIN " + sql.replace(":windows", "'$json'"), String::class.java).joinToString("\n")
        }!!

        assertTrue(plan(SQL_PAYMENTS_BY_METHOD).contains("ix_payment_transaction_revenue"), plan(SQL_PAYMENTS_BY_METHOD))
        assertTrue(plan(SQL_PAYMENT_BILLS_BY_WINDOW).contains("ix_payment_transaction_revenue"), plan(SQL_PAYMENT_BILLS_BY_WINDOW))
        assertTrue(plan(SQL_REFUNDS_BY_METHOD).contains("ix_refund_transaction_report"), plan(SQL_REFUNDS_BY_METHOD))
        assertTrue(plan(SQL_WRITE_OFFS_BY_WINDOW).contains("ix_orders_written_off_report"), plan(SQL_WRITE_OFFS_BY_WINDOW))
    }
}