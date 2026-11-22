package com.qrorder.common.error

import org.postgresql.util.PSQLException
import org.springframework.stereotype.Component
import java.sql.SQLException

/**
 * Kết quả dịch một lỗi PostgreSQL sang mã lỗi nghiệp vụ.
 *
 * [dbCode] (mã trigger) và [constraint] (tên ràng buộc) chỉ dùng để ghi log phía server,
 * không trả nguyên văn thông điệp database ra API (NFR-04).
 */
data class TranslatedDbError(
    val errorCode: ErrorCode,
    val sqlState: String?,
    val dbCode: String?,
    val constraint: String?,
    val serverMessage: String?,
)

/**
 * Dịch lỗi PostgreSQL (kể cả lỗi xảy ra lúc COMMIT do trigger DEFERRABLE) sang [ErrorCode].
 *
 * - SQLSTATE P0001 (RAISE EXCEPTION trong trigger): thông điệp có dạng `MA_LOI: mô tả`
 *   (DATABASE_DESIGN.md §1) -> [ErrorCode.fromTriggerCode].
 * - 23505 unique: theo tên constraint/index (bảng [UNIQUE_CONSTRAINTS]), mặc định DUPLICATE_RESOURCE.
 * - 23514 check: BUSINESS_RULE_VIOLATION (service lẽ ra đã validate trước).
 * - 23503 foreign key: INVALID_REFERENCE.
 * - 40001/40P01/55P03 (serialization, deadlock, lock): CONCURRENT_MODIFICATION.
 *
 * Duyệt toàn bộ chuỗi cause vì Spring/Hibernate bọc lỗi JDBC nhiều lớp
 * (DataIntegrityViolationException, JpaSystemException, TransactionSystemException...).
 */
@Component
class DatabaseErrorTranslator {

    fun translate(ex: Throwable): TranslatedDbError? {
        val sqlEx = findSqlException(ex) ?: return null
        val psql = sqlEx as? PSQLException
        val serverMessage = psql?.serverErrorMessage?.message ?: sqlEx.message
        val constraint = psql?.serverErrorMessage?.constraint
        val sqlState = sqlEx.sqlState

        val (code, dbCode) = when (sqlState) {
            SQLSTATE_RAISE_EXCEPTION -> {
                val triggerCode = extractTriggerCode(serverMessage)
                (ErrorCode.fromTriggerCode(triggerCode) ?: ErrorCode.DATA_INTEGRITY_VIOLATION) to triggerCode
            }
            SQLSTATE_UNIQUE_VIOLATION -> (UNIQUE_CONSTRAINTS[constraint] ?: ErrorCode.DUPLICATE_RESOURCE) to null
            SQLSTATE_CHECK_VIOLATION -> ErrorCode.BUSINESS_RULE_VIOLATION to null
            SQLSTATE_FOREIGN_KEY_VIOLATION -> ErrorCode.INVALID_REFERENCE to null
            SQLSTATE_NOT_NULL_VIOLATION -> ErrorCode.DATA_INTEGRITY_VIOLATION to null
            in CONCURRENCY_STATES -> ErrorCode.CONCURRENT_MODIFICATION to null
            else -> return null
        }
        return TranslatedDbError(code, sqlState, dbCode, constraint, serverMessage)
    }

    private fun findSqlException(ex: Throwable): SQLException? {
        var current: Throwable? = ex
        val seen = HashSet<Throwable>()
        var fallback: SQLException? = null
        while (current != null && seen.add(current)) {
            if (current is SQLException) {
                // BatchUpdateException giữ lỗi thật ở nextException.
                var next: SQLException? = current
                while (next != null) {
                    if (next is PSQLException) return next
                    next = next.nextException
                }
                if (fallback == null && current.sqlState != null) fallback = current
            }
            current = current.cause
        }
        return fallback
    }

    companion object {
        const val SQLSTATE_RAISE_EXCEPTION = "P0001"
        const val SQLSTATE_UNIQUE_VIOLATION = "23505"
        const val SQLSTATE_CHECK_VIOLATION = "23514"
        const val SQLSTATE_FOREIGN_KEY_VIOLATION = "23503"
        const val SQLSTATE_NOT_NULL_VIOLATION = "23502"
        private val CONCURRENCY_STATES = setOf("40001", "40P01", "55P03")

        // Thông điệp server: "MA_LOI: ..."; thông điệp JDBC dự phòng có tiền tố "ERROR: ".
        private val TRIGGER_CODE = Regex("^(?:ERROR:\\s*)?([A-Z][A-Z0-9_]+):")

        /** Unique constraint / unique index có ý nghĩa nghiệp vụ (DATABASE_DESIGN.md §4.2). */
        val UNIQUE_CONSTRAINTS: Map<String, ErrorCode> = mapOf(
            "ux_users_username_lower" to ErrorCode.USERNAME_TAKEN,
            "ux_category_name_lower" to ErrorCode.CATEGORY_NAME_TAKEN,
            "ux_option_item_group_name_active" to ErrorCode.OPTION_ITEM_NAME_TAKEN,
            "ux_dining_table_code_lower" to ErrorCode.TABLE_CODE_TAKEN,
            "ux_qr_code_active_per_table" to ErrorCode.QR_ALREADY_ACTIVE,
            "ux_table_session_active_per_table" to ErrorCode.SESSION_ALREADY_ACTIVE,
            "uq_orders_session" to ErrorCode.ORDER_ALREADY_EXISTS,
            "uq_order_batch_session_client_request" to ErrorCode.DUPLICATE_CLIENT_REQUEST,
            "uq_payment_transaction_request" to ErrorCode.DUPLICATE_REQUEST_ID,
            "uq_refund_transaction_request" to ErrorCode.DUPLICATE_REQUEST_ID,
            "ux_payment_transaction_provider_txn" to ErrorCode.DUPLICATE_PROVIDER_TXN,
            "ux_bill_current_per_order" to ErrorCode.BILL_ALREADY_ACTIVE,
            "ux_service_call_open_per_type" to ErrorCode.SERVICE_CALL_ALREADY_OPEN,
            "ux_user_permission_active" to ErrorCode.PERMISSION_ALREADY_GRANTED,
            "ux_item_cancel_request_pending" to ErrorCode.CANCEL_REQUEST_ALREADY_PENDING,
            // Hai lần rotation song song cùng family: bên chậm hơn coi như xung đột đồng thời.
            "ux_refresh_token_family_active" to ErrorCode.CONCURRENT_MODIFICATION,
        )

        fun extractTriggerCode(message: String?): String? =
            message?.trim()?.let { TRIGGER_CODE.find(it)?.groupValues?.get(1) }
    }
}
