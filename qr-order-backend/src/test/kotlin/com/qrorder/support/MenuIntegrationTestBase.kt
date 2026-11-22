package com.qrorder.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.qrorder.auth.domain.UserRole
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.event.ApplicationEventMulticaster
import org.springframework.messaging.SubscribableChannel
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.web.context.request.RequestContextHolder

/**
 * Nền cho integration test Giai đoạn 2 (menu): MockMvc + PostgreSQL thật (COMMIT thật), dịch vụ ảnh giả,
 * bộ ghi `/topic/menu`. Tài khoản 4 role tạo lười (chỉ khi test dùng tới).
 * Seed menu V2 được DbCleaner trả về nguyên trạng trước mỗi test.
 */
@AutoConfigureMockMvc
abstract class MenuIntegrationTestBase : IntegrationTestBase() {

    @Autowired protected lateinit var mvc: MockMvc
    @Autowired protected lateinit var objectMapper: ObjectMapper
    @Autowired protected lateinit var passwordEncoder: PasswordEncoder
    @Autowired protected lateinit var imageStorage: InMemoryImageStorageService
    @Autowired protected lateinit var eventMulticaster: ApplicationEventMulticaster

    @Autowired
    @Qualifier("brokerChannel")
    protected lateinit var brokerChannel: SubscribableChannel

    protected val api by lazy { ApiTestClient(mvc, objectMapper, jdbc, passwordEncoder) }
    protected lateinit var topic: MenuTopicRecorder

    protected val admin: LoggedInUser by lazy { api.loggedIn("admin_menu", UserRole.ADMIN) }
    protected val manager: LoggedInUser by lazy { api.loggedIn("manager_menu", UserRole.MANAGER) }
    protected val staff: LoggedInUser by lazy { api.loggedIn("staff_menu", UserRole.STAFF) }
    protected val kitchen: LoggedInUser by lazy { api.loggedIn("kitchen_menu", UserRole.KITCHEN) }

    @BeforeEach
    fun setUpMenu() {
        imageStorage.reset()
        topic = MenuTopicRecorder(brokerChannel, objectMapper).start()
    }

    @AfterEach
    fun tearDownMenu() {
        topic.stop()
        // Tránh rò rỉ ThreadLocal request giữa các test (cùng lý do với bản sửa ở AuditServiceIT).
        RequestContextHolder.resetRequestAttributes()
    }

    protected fun data(result: ResultActions): JsonNode = api.data(result)

    protected fun count(sql: String, vararg args: Any): Long = fx.count(sql, *args)

    protected fun categoryId(name: String): Long =
        jdbc.queryForObject("SELECT id FROM category WHERE name = ?", Long::class.java, name)!!

    protected fun productId(name: String): Long = fx.productId(name)

    protected fun optionGroupId(name: String): Long =
        jdbc.queryForObject("SELECT id FROM option_group WHERE name = ?", Long::class.java, name)!!

    protected fun optionItemId(groupName: String, itemName: String): Long =
        jdbc.queryForObject(
            "SELECT i.id FROM option_item i JOIN option_group g ON g.id = i.option_group_id WHERE g.name = ? AND i.name = ?",
            Long::class.java, groupName, itemName,
        )!!

    protected fun version(table: String, id: Long): Long =
        jdbc.queryForObject("SELECT version FROM $table WHERE id = ?", Long::class.java, id)!!

    /** Tạo nhanh danh mục qua API (ADMIN), trả về JSON data. */
    protected fun createCategory(name: String, displayOrder: Int = 10, token: String = admin.accessToken): JsonNode =
        data(api.post("/api/v1/categories", token, mapOf("name" to name, "displayOrder" to displayOrder)))

    /** Tạo nhanh món qua API (ADMIN), trả về JSON data. */
    protected fun createProduct(
        categoryId: Long,
        name: String,
        price: Long = 50_000,
        displayOrder: Int = 1,
        token: String = admin.accessToken,
    ): JsonNode = data(
        api.post(
            "/api/v1/products", token,
            mapOf("categoryId" to categoryId, "name" to name, "price" to price, "displayOrder" to displayOrder),
        ),
    )
}
