package com.qrorder.schema

import com.qrorder.support.IntegrationTestBase
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.Table
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.math.BigDecimal

/**
 * Đường DEV: Flyway chạy thật V1 -> V2 (seed) -> V3, sau đó Hibernate `ddl-auto: validate` đối chiếu
 * 26 entity với schema. Spring context khởi động thành công = validate PASS (nếu lệch, context lỗi
 * ngay khi khởi tạo EntityManagerFactory). Đóng DB-13 (chạy Flyway thật).
 */
class DevMigrationAndSchemaIT : IntegrationTestBase() {

    @Autowired
    lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired
    lateinit var environment: Environment

    @Test
    fun `flyway applied V1, V2 seed, V3 successfully`() {
        val history = jdbc.queryForList(
            "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
        )
        assertEquals(listOf("1", "2", "3"), history.map { it["version"] })
        assertTrue(history.all { it["success"] == true })
    }

    @Test
    fun `schema has exactly the 26 designed tables`() {
        assertEquals(EXPECTED_TABLES, tablesInDatabase(jdbc))
    }

    @Test
    fun `hibernate validated 26 entities mapped one-to-one with tables`() {
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"))
        val mappedTables = entityManagerFactory.metamodel.entities
            .map { it.javaType.getAnnotation(Table::class.java).name }
            .toSortedSet()
        assertEquals(26, entityManagerFactory.metamodel.entities.size)
        assertEquals(EXPECTED_TABLES, mappedTables)
    }

    @Test
    fun `dev seed is present (VAT 8 percent test value, tables, QR, menu)`() {
        val vat = jdbc.queryForObject("SELECT vat_rate FROM restaurant_settings WHERE id = 1", BigDecimal::class.java)
        assertEquals(0, BigDecimal("0.0800").compareTo(vat))
        assertEquals(11L, fx.count("SELECT count(*) FROM dining_table"))
        assertEquals(10L, fx.count("SELECT count(*) FROM qr_code WHERE status = 'ACTIVE'"))
        assertEquals(12L, fx.count("SELECT count(*) FROM product"))
        assertEquals(0L, fx.count("SELECT count(*) FROM users"), "Seed không được tạo tài khoản (BR-A09)")
    }

    @Test
    fun `database objects match DATABASE_DESIGN v1_1 counts`() {
        assertEquals(44L, fx.count("SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal"))
        assertEquals(
            9L,
            fx.count(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexdef LIKE 'CREATE UNIQUE INDEX%WHERE%'",
            ),
        )
    }

    companion object {
        val EXPECTED_TABLES = sortedSetOf(
            "audit_log", "bill", "category", "daily_sequence", "dining_table", "item_cancel_request",
            "option_group", "option_item", "order_batch", "order_item", "order_item_option",
            "order_item_status_history", "orders", "payment_transaction", "product", "product_option_group",
            "qr_code", "refresh_token", "refund_transaction", "restaurant_settings", "service_call",
            "session_device", "table_session", "transaction_status_history", "user_permission", "users",
        )

        fun tablesInDatabase(jdbc: org.springframework.jdbc.core.JdbcTemplate): Set<String> =
            jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' " +
                    "AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'",
                String::class.java,
            ).toSortedSet()
    }
}
