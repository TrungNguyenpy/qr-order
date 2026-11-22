package com.qrorder.schema

import com.qrorder.support.TestcontainersConfig
import jakarta.persistence.EntityManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Đường PRODUCTION: chỉ `classpath:db/migration` (giống application-prod.yml) trên database trống:
 * Flyway áp dụng V1 rồi V3 (khoảng trống V2 là seed dev), không có dữ liệu mẫu, vat_rate NULL,
 * và Hibernate validate vẫn PASS. Context và container riêng (thuộc tính khác context dev).
 */
@SpringBootTest(properties = ["spring.flyway.locations=classpath:db/migration"])
@ActiveProfiles("test")
@Import(TestcontainersConfig::class)
class ProductionMigrationIT {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun `production path applies V1 then V3 without seed`() {
        val versions = jdbc.queryForList(
            "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL AND success ORDER BY installed_rank",
            String::class.java,
        )
        assertEquals(listOf("1", "3"), versions)
        assertEquals(DevMigrationAndSchemaIT.EXPECTED_TABLES, DevMigrationAndSchemaIT.tablesInDatabase(jdbc))
        assertEquals(26, entityManagerFactory.metamodel.entities.size)
    }

    @Test
    fun `production database has only the single default settings row and no sample data`() {
        assertEquals(1L, count("SELECT count(*) FROM restaurant_settings"))
        assertNull(jdbc.queryForObject("SELECT vat_rate FROM restaurant_settings WHERE id = 1", java.math.BigDecimal::class.java))
        listOf("users", "dining_table", "qr_code", "category", "product", "option_group").forEach {
            assertEquals(0L, count("SELECT count(*) FROM $it"), "Bảng $it phải trống ở production")
        }
    }

    private fun count(sql: String): Long = jdbc.queryForObject(sql, Long::class.java)!!
}
