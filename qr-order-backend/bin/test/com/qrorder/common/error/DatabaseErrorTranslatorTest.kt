package com.qrorder.common.error

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.TransactionSystemException
import java.sql.BatchUpdateException
import java.sql.SQLException

class DatabaseErrorTranslatorTest {

    private val translator = DatabaseErrorTranslator()

    /** Thông điệp lỗi theo giao thức PostgreSQL: các trường "<mã><giá trị>" ngăn bởi NUL. */
    private fun psql(sqlState: String, message: String, constraint: String? = null): PSQLException {
        val fields = buildString {
            append("SERROR\u0000")
            append("C").append(sqlState).append('\u0000')
            append("M").append(message).append('\u0000')
            if (constraint != null) append("n").append(constraint).append('\u0000')
            append('\u0000')
        }
        return PSQLException(ServerErrorMessage(fields))
    }

    @Test
    fun `trigger code is read from the message prefix even when deeply wrapped`() {
        val ex = TransactionSystemException("commit thất bại", RuntimeException("x", psql("P0001", "BILL_PAYMENT_STATUS_MISMATCH: Bill ...")))
        val t = translator.translate(ex)!!
        assertEquals(ErrorCode.BILL_PAYMENT_STATUS_MISMATCH, t.errorCode)
        assertEquals("BILL_PAYMENT_STATUS_MISMATCH", t.dbCode)
        assertEquals("P0001", t.sqlState)
    }

    @Test
    fun `unknown trigger code falls back to DATA_INTEGRITY_VIOLATION`() {
        assertEquals(ErrorCode.DATA_INTEGRITY_VIOLATION, translator.translate(psql("P0001", "MA_MOI_CHUA_KHAI_BAO: ..."))!!.errorCode)
    }

    @Test
    fun `named unique constraints map to business codes, others to DUPLICATE_RESOURCE`() {
        val wrapped = DataIntegrityViolationException("dup", psql("23505", "duplicate key", "ux_table_session_active_per_table"))
        assertEquals(ErrorCode.SESSION_ALREADY_ACTIVE, translator.translate(wrapped)!!.errorCode)
        assertEquals(ErrorCode.DUPLICATE_RESOURCE, translator.translate(psql("23505", "dup", "uq_khac"))!!.errorCode)
    }

    @Test
    fun `check, foreign key and concurrency states are classified`() {
        assertEquals(ErrorCode.BUSINESS_RULE_VIOLATION, translator.translate(psql("23514", "check", "ck_bill_net"))!!.errorCode)
        assertEquals(ErrorCode.INVALID_REFERENCE, translator.translate(psql("23503", "fk", "fk_bill_order"))!!.errorCode)
        assertEquals(ErrorCode.CONCURRENT_MODIFICATION, translator.translate(psql("40P01", "deadlock"))!!.errorCode)
    }

    @Test
    fun `real error hidden in BatchUpdateException next exception is found`() {
        val batch = BatchUpdateException("batch", intArrayOf())
        batch.nextException = psql("P0001", "ORDER_NOT_OPEN: Order 1 đang LOCKED")
        assertEquals(ErrorCode.ORDER_NOT_OPEN, translator.translate(RuntimeException(batch))!!.errorCode)
    }

    @Test
    fun `non database exceptions are not translated`() {
        assertNull(translator.translate(IllegalStateException("không phải lỗi DB")))
        assertNull(translator.translate(SQLException("không có sqlState")))
    }

    @Test
    fun `extractTriggerCode accepts server message and JDBC message with ERROR prefix`() {
        assertEquals("LAST_ACTIVE_ADMIN", DatabaseErrorTranslator.extractTriggerCode("LAST_ACTIVE_ADMIN: không thể ..."))
        assertEquals("LAST_ACTIVE_ADMIN", DatabaseErrorTranslator.extractTriggerCode("ERROR: LAST_ACTIVE_ADMIN: không thể ..."))
        assertNull(DatabaseErrorTranslator.extractTriggerCode("lỗi thường"))
    }
}
