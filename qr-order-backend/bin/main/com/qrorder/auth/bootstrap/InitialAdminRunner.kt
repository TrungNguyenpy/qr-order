package com.qrorder.auth.bootstrap

import com.qrorder.auth.domain.User
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.password.PasswordPolicy
import com.qrorder.auth.password.PasswordService
import com.qrorder.auth.repository.UserRepository
import com.qrorder.common.audit.AuditActions
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEntityTypes
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Thông tin ADMIN đầu tiên, lấy từ biến môi trường INITIAL_ADMIN_USERNAME, INITIAL_ADMIN_PASSWORD,
 * INITIAL_ADMIN_FULL_NAME (application.yml: app.bootstrap.initial-admin.*). Không có giá trị mặc định.
 */
@ConfigurationProperties(prefix = "app.bootstrap.initial-admin")
data class InitialAdminProperties(
    val username: String = "",
    val password: String = "",
    val fullName: String = "",
) {
    override fun toString() = "InitialAdminProperties(username=$username, password=***, fullName=$fullName)"
}

/**
 * Khởi tạo tài khoản ADMIN đầu tiên khi triển khai (BR-A09; người dùng chốt 20/11/2026).
 *
 * - Chỉ chạy khi bảng users CHƯA CÓ bản ghi nào. Đã có user thì không làm gì (kể cả khi biến môi trường còn).
 * - Thiếu biến môi trường: chỉ ghi log cảnh báo tên biến còn thiếu và bỏ qua (ứng dụng vẫn khởi động).
 * - Giá trị không hợp lệ (username sai định dạng, mật khẩu không đạt chính sách): ghi log lỗi và bỏ qua.
 * - Không bắt buộc đổi mật khẩu ở lần đăng nhập đầu (giữ nguyên schema V1-V3).
 * - Không bao giờ ghi mật khẩu ra log; mật khẩu băm BCrypt; audit USER_CREATED với actor SYSTEM.
 * - Advisory lock + kiểm tra lại trong transaction: hai instance khởi động cùng lúc không tạo hai ADMIN.
 */
@Component
class InitialAdminRunner(
    private val properties: InitialAdminProperties,
    private val userRepository: UserRepository,
    private val passwordService: PasswordService,
    private val auditService: AuditService,
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : ApplicationRunner {

    enum class Outcome { CREATED, SKIPPED_USERS_EXIST, SKIPPED_MISSING_CONFIG, SKIPPED_INVALID_CONFIG }

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    override fun run(args: ApplicationArguments) {
        createIfNoUsers()
    }

    fun createIfNoUsers(): Outcome {
        if (userRepository.count() > 0) {
            if (properties.password.isNotEmpty()) {
                log.warn("Bảng users đã có dữ liệu nên bỏ qua khởi tạo ADMIN đầu tiên; nên xóa INITIAL_ADMIN_PASSWORD khỏi môi trường")
            }
            return Outcome.SKIPPED_USERS_EXIST
        }

        val missing = buildList {
            if (properties.username.isBlank()) add(ENV_USERNAME)
            if (properties.password.isEmpty()) add(ENV_PASSWORD)
            if (properties.fullName.isBlank()) add(ENV_FULL_NAME)
        }
        if (missing.isNotEmpty()) {
            log.warn(
                "Chưa có tài khoản nào và thiếu biến môi trường {} - bỏ qua khởi tạo ADMIN đầu tiên. " +
                    "Đặt đủ {}, {}, {} rồi khởi động lại để tạo ADMIN.",
                missing, ENV_USERNAME, ENV_PASSWORD, ENV_FULL_NAME,
            )
            return Outcome.SKIPPED_MISSING_CONFIG
        }

        val username = properties.username.trim()
        val fullName = properties.fullName.trim()
        val problems = buildList {
            if (!USERNAME_FORMAT.matches(username)) {
                add("$ENV_USERNAME phải 3-50 ký tự, chỉ gồm chữ không dấu, số, dấu chấm, gạch dưới, gạch ngang")
            }
            if (fullName.length > FULL_NAME_MAX) add("$ENV_FULL_NAME tối đa $FULL_NAME_MAX ký tự")
            if (!PasswordPolicy.isValid(properties.password)) add("$ENV_PASSWORD: ${PasswordPolicy.MESSAGE}")
        }
        if (problems.isNotEmpty()) {
            log.error("Không tạo ADMIN đầu tiên vì cấu hình không hợp lệ: {}", problems)
            return Outcome.SKIPPED_INVALID_CONFIG
        }

        val outcome = tx.execute {
            // Tuần tự hóa giữa các instance; kiểm tra lại sau khi giữ khóa.
            jdbcTemplate.execute("SELECT pg_advisory_xact_lock(hashtext('$ADVISORY_LOCK_KEY'))")
            if (userRepository.count() > 0) return@execute Outcome.SKIPPED_USERS_EXIST

            val admin = userRepository.saveAndFlush(
                User(
                    username = username,
                    passwordHash = passwordService.encodeValidated(properties.password, ENV_PASSWORD),
                    fullName = fullName,
                    role = UserRole.ADMIN,
                ),
            )
            auditService.record(
                AuditActor.System,
                AuditEvent(
                    action = AuditActions.USER_CREATED,
                    entityType = AuditEntityTypes.USERS,
                    entityId = admin.id,
                    newValue = mapOf(
                        "username" to admin.username,
                        "fullName" to admin.fullName,
                        "role" to admin.role,
                        "status" to admin.status,
                        "source" to "INITIAL_ADMIN_RUNNER",
                    ),
                    reason = "Khởi tạo tài khoản ADMIN đầu tiên từ biến môi trường",
                ),
            )
            log.info(
                "Đã tạo tài khoản ADMIN đầu tiên username={} (id={}). Nên xóa {} khỏi môi trường sau khi đăng nhập.",
                admin.username, admin.id, ENV_PASSWORD,
            )
            Outcome.CREATED
        }
        return outcome ?: Outcome.SKIPPED_USERS_EXIST
    }

    companion object {
        const val ENV_USERNAME = "INITIAL_ADMIN_USERNAME"
        const val ENV_PASSWORD = "INITIAL_ADMIN_PASSWORD"
        const val ENV_FULL_NAME = "INITIAL_ADMIN_FULL_NAME"
        private const val ADVISORY_LOCK_KEY = "qr_order.initial_admin"
        private const val FULL_NAME_MAX = 100
        private val USERNAME_FORMAT = Regex("^[A-Za-z0-9._-]{3,50}$")
    }
}
