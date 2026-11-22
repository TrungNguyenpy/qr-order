package com.qrorder.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.qrorder.auth.domain.UserRole
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Tài khoản test đã đăng nhập: id, tên, mật khẩu gốc và cặp token. */
data class LoggedInUser(
    val id: Long,
    val username: String,
    val password: String,
    val accessToken: String,
    val refreshToken: String,
)

/**
 * Gọi API qua MockMvc (HTTP thật trong tiến trình, PostgreSQL thật) cho integration test Giai đoạn 1.
 * User được tạo bằng SQL với mật khẩu băm bằng chính PasswordEncoder của ứng dụng để đăng nhập được.
 */
class ApiTestClient(
    private val mvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val passwordEncoder: PasswordEncoder,
) {
    fun createUser(
        username: String,
        role: UserRole,
        password: String = DEFAULT_PASSWORD,
        status: String = "ACTIVE",
    ): Long =
        jdbc.queryForObject(
            "INSERT INTO users (username, password_hash, full_name, role, status) VALUES (?, ?, ?, ?, ?) RETURNING id",
            Long::class.java, username, passwordEncoder.encode(password), "Nhân viên $username", role.name, status,
        )!!

    /** Tạo user rồi đăng nhập qua POST /api/v1/auth/login. */
    fun loggedIn(username: String, role: UserRole, password: String = DEFAULT_PASSWORD): LoggedInUser {
        val id = createUser(username, role, password)
        val data = loginData(username, password)
        return LoggedInUser(id, username, password, data["accessToken"].asText(), data["refreshToken"].asText())
    }

    fun login(username: String, password: String): ResultActions =
        post("/api/v1/auth/login", null, mapOf("username" to username, "password" to password))

    fun loginData(username: String, password: String): JsonNode =
        data(login(username, password).andExpect(status().isOk))

    fun refresh(refreshToken: String): ResultActions =
        post("/api/v1/auth/refresh", null, mapOf("refreshToken" to refreshToken))

    fun get(path: String, token: String?): ResultActions = perform(MockMvcRequestBuilders.get(path), token, null)

    fun post(path: String, token: String?, body: Any?): ResultActions = perform(MockMvcRequestBuilders.post(path), token, body)

    fun put(path: String, token: String?, body: Any?): ResultActions = perform(MockMvcRequestBuilders.put(path), token, body)

    fun patch(path: String, token: String?, body: Any?): ResultActions = perform(MockMvcRequestBuilders.patch(path), token, body)

    fun delete(path: String, token: String?): ResultActions = perform(MockMvcRequestBuilders.delete(path), token, null)

    /** Upload multipart (Giai đoạn 2: ảnh món). [file] null = không gửi phần file. */
    fun multipart(path: String, token: String?, file: MockMultipartFile?, params: Map<String, String> = emptyMap()): ResultActions {
        val builder = MockMvcRequestBuilders.multipart(path)
        if (file != null) builder.file(file)
        params.forEach { (k, v) -> builder.param(k, v) }
        if (token != null) builder.header("Authorization", "Bearer $token")
        return mvc.perform(builder)
    }

    /** Gửi chuỗi JSON thô (để thử giá trị enum không tồn tại, JSON sai...). */
    fun postRaw(path: String, token: String?, json: String): ResultActions =
        mvc.perform(
            MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .apply { if (token != null) header("Authorization", "Bearer $token") },
        )

    fun perform(builder: MockHttpServletRequestBuilder, token: String?, body: Any?, correlationId: String? = null): ResultActions {
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (correlationId != null) builder.header("X-Correlation-Id", correlationId)
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))
        return mvc.perform(builder)
    }

    fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.contentAsByteArray)

    fun data(result: ResultActions): JsonNode = json(result)["data"]

    /** Các dòng audit_log của một action, cũ trước (đọc bằng SQL để kiểm tra đúng cột trong database). */
    fun auditRows(action: String): List<Map<String, Any?>> =
        jdbc.queryForList(
            """SELECT id, actor_type, actor_user_id, action, entity_type, entity_id,
                      old_value::text AS old_value, new_value::text AS new_value, reason, reauthenticated,
                      correlation_id, host(ip_address) AS ip_address, occurred_at
                 FROM audit_log WHERE action = ? ORDER BY id""",
            action,
        )

    fun jsonOf(text: Any?): JsonNode = objectMapper.readTree(text as String)

    fun activeRefreshTokens(userId: Long): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", Long::class.java, userId,
        )!!

    fun revokeReasons(userId: Long): List<String> =
        jdbc.queryForList(
            "SELECT DISTINCT revoke_reason FROM refresh_token WHERE user_id = ? AND revoke_reason IS NOT NULL ORDER BY 1",
            String::class.java, userId,
        )

    companion object {
        /** Đạt chính sách mật khẩu Giai đoạn 1 (>= 8 ký tự, có hoa, thường, số). */
        const val DEFAULT_PASSWORD = "Matkhau123"

        /** Kiểm tra định dạng lỗi ApiResponse. */
        fun ResultActions.isError(httpStatus: Int, code: String): ResultActions =
            andExpect(status().`is`(httpStatus))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code))
    }
}
