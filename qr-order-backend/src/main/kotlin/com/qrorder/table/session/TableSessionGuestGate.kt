package com.qrorder.table.session

import com.qrorder.auth.security.GuestSessionCheck
import com.qrorder.auth.security.GuestSessionGate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * BE-05: cài đặt [GuestSessionGate] của module auth. Một truy vấn theo khóa chính của table_session (kèm EXISTS theo
 * unique index (session_id, device_id) của session_device) cho mỗi request/CONNECT có Guest JWT.
 */
@Component
class TableSessionGuestGate(private val jdbc: JdbcTemplate) : GuestSessionGate {
    override fun check(sessionId: Long, deviceId: String): GuestSessionCheck {
        val rows = jdbc.query(
            """SELECT s.status,
                      EXISTS (SELECT 1 FROM session_device d WHERE d.session_id = s.id AND d.device_id = ?) AS device_known
                 FROM table_session s
                WHERE s.id = ?""",
            { rs, _ -> rs.getString("status") to rs.getBoolean("device_known") },
            deviceId, sessionId,
        )
        val (status, deviceKnown) = rows.firstOrNull() ?: return GuestSessionCheck.SESSION_CLOSED
        return when {
            status != "OPEN" && status != "BILLING" -> GuestSessionCheck.SESSION_CLOSED
            !deviceKnown -> GuestSessionCheck.DEVICE_UNKNOWN
            else -> GuestSessionCheck.ACTIVE
        }
    }
}
