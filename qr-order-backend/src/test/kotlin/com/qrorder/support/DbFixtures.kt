package com.qrorder.support

import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.UserRole
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Tạo dữ liệu test bằng SQL (giống tools/schema-test/test_schema.py), COMMIT ngay.
 * Bàn và món lấy từ seed dev V2 (T01..T10, "Phở bò tái" 65.000).
 */
class DbFixtures(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
) {
    fun user(username: String, role: UserRole, status: String = "ACTIVE"): Long =
        jdbc.queryForObject(
            "INSERT INTO users (username, password_hash, full_name, role, status) VALUES (?, 'x', ?, ?, ?) RETURNING id",
            Long::class.java, username, username, role.name, status,
        )!!

    fun grant(userId: Long, permission: Permission, grantedBy: Long): Long =
        jdbc.queryForObject(
            "INSERT INTO user_permission (user_id, permission, granted_by, reason, reauthenticated) " +
                "VALUES (?, ?, ?, 'test', ?) RETURNING id",
            Long::class.java, userId, permission.name, grantedBy, userId == grantedBy,
        )!!

    fun revoke(grantId: Long, revokedBy: Long) {
        jdbc.update(
            "UPDATE user_permission SET revoked_at = now(), revoked_by = ?, revoke_reason = 'test' WHERE id = ?",
            revokedBy, grantId,
        )
    }

    fun tableId(code: String): Long =
        jdbc.queryForObject("SELECT id FROM dining_table WHERE code = ?", Long::class.java, code)!!

    fun productId(name: String = PHO): Long =
        jdbc.queryForObject("SELECT id FROM product WHERE name = ?", Long::class.java, name)!!

    /** Phiên OPEN (khách quét QR) + Order OPEN của phiên. Trả về (sessionId, orderId). */
    fun openSessionWithOrder(tableCode: String): Pair<Long, Long> {
        val sessionId = jdbc.queryForObject(
            "INSERT INTO table_session (table_id, opened_by_type) VALUES (?, 'GUEST') RETURNING id",
            Long::class.java, tableId(tableCode),
        )!!
        val orderId = jdbc.queryForObject(
            "INSERT INTO orders (session_id) VALUES (?) RETURNING id", Long::class.java, sessionId,
        )!!
        return sessionId to orderId
    }

    /**
     * Phiên OPEN (khách quét QR) có sẵn thiết bị [deviceId] - đủ để Guest JWT (sid, did) qua được kiểm tra BE-05.
     * Không đổi trạng thái bàn (fixture SQL thuần cho test hạ tầng).
     */
    fun guestSession(tableCode: String, deviceId: String = "device-0001"): Long {
        val sessionId = jdbc.queryForObject(
            "INSERT INTO table_session (table_id, opened_by_type) VALUES (?, 'GUEST') RETURNING id",
            Long::class.java, tableId(tableCode),
        )!!
        jdbc.update("INSERT INTO session_device (session_id, device_id) VALUES (?, ?)", sessionId, deviceId)
        return sessionId
    }

    fun addBatch(sessionId: Long, orderId: Long, seq: Int, status: String = "ACCEPTED"): Long =
        jdbc.queryForObject(
            """INSERT INTO order_batch (order_id, session_id, seq, client_request_id, status,
                   requires_confirmation, submitted_by_type, device_id)
               VALUES (?, ?, ?, ?, ?, false, 'GUEST', 'device-0001') RETURNING id""",
            Long::class.java, orderId, sessionId, seq, UUID.randomUUID(), status,
        )!!

    fun addConfirmedItem(orderId: Long, batchId: Long, price: Long): Long =
        jdbc.queryForObject(
            """INSERT INTO order_item (order_id, batch_id, product_id, product_name, unit_price, quantity,
                   line_total, status, device_id, confirmed_at)
               VALUES (?, ?, ?, ?, ?, 1, ?, 'CONFIRMED', 'device-0001', now()) RETURNING id""",
            Long::class.java, orderId, batchId, productId(), PHO, price, price,
        )!!

    /** VAT trích ngược đúng công thức CHECK ck_bill_vat_amount (BR-P02), tính bằng numeric của PostgreSQL. */
    fun vatOf(total: Long, vatRate: String = "0.0800"): Long =
        jdbc.queryForObject(
            "SELECT round(?::numeric * ?::numeric / (1 + ?::numeric))::bigint",
            Long::class.java, total, vatRate, vatRate,
        )!!

    /** Tính tiền: phiên BILLING, Order LOCKED, Bill ACTIVE UNPAID (giảm giá 0, phí 0, VAT 8%). Trả về billId. */
    fun lockAndBill(sessionId: Long, orderId: Long, total: Long, createdBy: Long): Long =
        tx.execute {
            jdbc.update("UPDATE table_session SET status = 'BILLING', billing_started_at = now() WHERE id = ?", sessionId)
            jdbc.update("UPDATE orders SET status = 'LOCKED' WHERE id = ?", orderId)
            jdbc.queryForObject(
                """INSERT INTO bill (order_id, bill_number, subtotal_amount, net_amount, service_fee_rate,
                       service_fee_amount, total_amount, vat_rate, vat_amount, created_by)
                   VALUES (?, NULL, ?, ?, 0, 0, ?, 0.0800, ?, ?) RETURNING id""",
                Long::class.java, orderId, total, total, total, vatOf(total), createdBy,
            )
        }!!

    /** Ghi Payment SUCCESS và đặt payment_status trong CÙNG transaction (BR-P11). */
    fun pay(billId: Long, amount: Long, newPaymentStatus: String, by: Long) {
        tx.executeWithoutResult {
            jdbc.update(
                """INSERT INTO payment_transaction (bill_id, request_id, method, amount, status, created_by,
                       confirmed_by, confirmed_at)
                   VALUES (?, ?, 'CASH', ?, 'SUCCESS', ?, ?, now())""",
                billId, UUID.randomUUID(), amount, by, by,
            )
            jdbc.update(
                "UPDATE bill SET payment_status = ?, paid_at = CASE WHEN ? = 'PAID' THEN now() ELSE paid_at END, " +
                    "version = version + 1 WHERE id = ?",
                newPaymentStatus, newPaymentStatus, billId,
            )
        }
    }

    fun requestVoid(billId: Long, orderId: Long, managerId: Long, orderStatusBefore: String) {
        tx.executeWithoutResult {
            jdbc.update(
                "UPDATE bill SET status = 'VOID_PENDING', void_requested_by = ?, void_requested_at = now(), " +
                    "void_reason = 'Sai món' WHERE id = ?",
                managerId, billId,
            )
            jdbc.update("UPDATE orders SET status = 'LOCKED', status_before_void = ? WHERE id = ?", orderStatusBefore, orderId)
        }
    }

    /** Refund (SUCCESS có confirmed_by/at; PENDING thì không); [newPaymentStatus] cập nhật cùng transaction. */
    fun refund(billId: Long, amount: Long, status: String, by: Long, newPaymentStatus: String? = null) {
        tx.executeWithoutResult {
            val pending = status == "PENDING"
            jdbc.update(
                """INSERT INTO refund_transaction (bill_id, request_id, method, amount, status, reason, created_by,
                       confirmed_by, confirmed_at)
                   VALUES (?, ?, 'CASH', ?, ?, 'Hoàn tiền Void', ?, ?, CASE WHEN ? THEN NULL ELSE now() END)""",
                billId, UUID.randomUUID(), amount, status, by, if (pending) null else by, pending,
            )
            if (newPaymentStatus != null) {
                jdbc.update("UPDATE bill SET payment_status = ? WHERE id = ?", newPaymentStatus, billId)
            }
        }
    }

    fun count(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!

    companion object {
        const val PHO = "Phở bò tái"
    }
}
