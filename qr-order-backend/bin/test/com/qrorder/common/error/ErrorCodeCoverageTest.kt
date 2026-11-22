package com.qrorder.common.error

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * Bảo đảm mọi mã lỗi trigger trong migration (RAISE EXCEPTION 'MA_LOI: ...') đều có ErrorCode tương ứng,
 * và ngược lại không có ErrorCode DB_TRIGGER "mồ côi". Thêm migration V4+ có mã mới mà quên khai báo
 * ErrorCode thì test này đỏ.
 */
class ErrorCodeCoverageTest {

    private val migrations = listOf(
        "/db/migration/V1__init_schema.sql",
        "/db/migration/V3__add_refresh_token_and_item_history.sql",
    )

    private fun sql(): String = migrations.joinToString("\n") {
        requireNotNull(javaClass.getResource(it)) { "Không thấy $it" }.readText()
    }

    private fun triggerCodesInSql(): Set<String> =
        Regex("RAISE EXCEPTION '([A-Z][A-Z0-9_]+):").findAll(sql()).map { it.groupValues[1] }.toSortedSet()

    @Test
    fun `every trigger code in the migrations has an ErrorCode`() {
        val missing = triggerCodesInSql().filter { ErrorCode.fromTriggerCode(it) == null }
        assertTrue(missing.isEmpty(), "Thiếu ErrorCode cho mã trigger: $missing")
    }

    @Test
    fun `every DB_TRIGGER ErrorCode exists in the migrations`() {
        val declared = ErrorCode.entries.filter { it.source == ErrorCode.Source.DB_TRIGGER }.map { it.name }.toSortedSet()
        assertEquals(triggerCodesInSql(), declared)
    }

    @Test
    fun `mapped unique constraints exist in the migrations`() {
        val text = sql()
        DatabaseErrorTranslator.UNIQUE_CONSTRAINTS.keys.forEach {
            assertTrue(text.contains(it), "Không thấy constraint/index $it trong migration")
        }
    }

    @Test
    fun `required SRS mappings have the agreed HTTP status`() {
        assertEquals(HttpStatus.CONFLICT, ErrorCode.LAST_ACTIVE_ADMIN.httpStatus)
        assertEquals(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_OPEN.httpStatus)
        assertEquals(HttpStatus.CONFLICT, ErrorCode.INVALID_ORDER_ITEM_TRANSITION.httpStatus)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.PAYMENT_EXCEEDS_REMAINING.httpStatus)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.REFUND_EXCEEDS_REFUNDABLE.httpStatus)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.REFRESH_TOKEN_ROTATION_INCOMPLETE.httpStatus)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VAT_NOT_CONFIGURED.httpStatus) // SRS §6.8
        assertEquals(HttpStatus.CONFLICT, ErrorCode.BATCH_PENDING.httpStatus) // SRS §6.8
    }
}
