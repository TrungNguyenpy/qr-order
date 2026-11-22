package com.qrorder.support

import com.qrorder.common.error.DatabaseErrorTranslator
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.TranslatedDbError
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Nền cho integration test với PostgreSQL thật.
 *
 * QUAN TRỌNG (DATABASE_DESIGN.md §5.3 (1)): trigger DEFERRABLE (bất biến Bill, rotation refresh token)
 * chỉ chạy lúc COMMIT. Vì vậy test KHÔNG dùng @Transactional rollback của Spring test; mọi thao tác
 * ghi chạy trong [tx] (TransactionTemplate) và COMMIT thật. Dữ liệu được dọn trước mỗi test bởi [DbCleaner].
 *
 * Giai đoạn 2: [TestImageStorageConfig] thay dịch vụ lưu ảnh Cloudinary bằng [InMemoryImageStorageService] (@Primary)
 * cho mọi IT - không gọi mạng thật; mọi IT dùng chung cấu hình nên dùng chung Spring context/container.
 * Giai đoạn 3: [TestSessionHooksConfig] thêm một validator/listener force-close có thể bật/tắt lúc chạy (mặc định
 * không làm gì) để kiểm chứng điểm mở rộng SessionForceCloseValidator/Listener.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig::class, TestImageStorageConfig::class, TestSessionHooksConfig::class)
abstract class IntegrationTestBase {

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    @Autowired
    protected lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    protected lateinit var dbErrorTranslator: DatabaseErrorTranslator

    @PersistenceContext
    protected lateinit var em: EntityManager

    protected val tx: TransactionTemplate by lazy { TransactionTemplate(transactionManager) }

    protected val fx: DbFixtures by lazy { DbFixtures(jdbc, tx) }

    @BeforeEach
    fun cleanDatabase() {
        DbCleaner.clean(jdbc, tx)
    }

    /** Chạy [block], bắt buộc lỗi database và trả về kết quả dịch; kiểm tra mã lỗi mong đợi. */
    protected fun expectDbError(expected: ErrorCode, block: () -> Unit): TranslatedDbError {
        val ex = assertFailsWith<Throwable> { block() }
        val translated = dbErrorTranslator.translate(ex)
        assertNotNull(translated, "Không dịch được lỗi: $ex")
        assertEquals(expected, translated!!.errorCode, "Mã lỗi khác mong đợi. Thông điệp DB: ${translated.serverMessage}")
        return translated
    }
}
