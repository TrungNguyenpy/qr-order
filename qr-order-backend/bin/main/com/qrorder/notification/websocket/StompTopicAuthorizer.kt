package com.qrorder.notification.websocket

import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.security.AuthPrincipal
import com.qrorder.auth.security.GuestPrincipal
import com.qrorder.auth.security.StaffPrincipal
import org.springframework.stereotype.Component

/**
 * Luật SUBSCRIBE theo kênh STOMP (SRS §11.4, §10 "kiểm tra quyền ở cả REST và STOMP"):
 *
 * | Topic                         | Được subscribe                         |
 * |-------------------------------|----------------------------------------|
 * | /topic/session/{sessionId}    | Guest của đúng phiên đó; STAFF, MANAGER |
 * | /topic/staff/orders, /ready   | STAFF, MANAGER                          |
 * | /topic/tables                 | STAFF, MANAGER                          |
 * | /topic/kitchen, /topic/kitchen/orders | KITCHEN, MANAGER                 |
 * | /topic/menu                   | mọi client đã xác thực                  |
 * | /user/queue/...               | mọi client đã xác thực (kênh riêng)     |
 *
 * ADMIN không nằm trong các kênh vận hành (không kế thừa quyền Manager/Staff, BR-A06).
 * Mọi topic khác: từ chối (mặc định từ chối, BR-A01).
 */
@Component
class StompTopicAuthorizer {

    fun canSubscribe(principal: AuthPrincipal, destination: String?): Boolean {
        if (destination == null) return false
        val path = destination.trimEnd('/')
        if (path.contains("..") || path.contains('*')) return false

        SESSION_TOPIC.matchEntire(path)?.let { match ->
            val sessionId = match.groupValues[1].toLongOrNull() ?: return false
            return when (principal) {
                is GuestPrincipal -> principal.sessionId == sessionId
                is StaffPrincipal -> principal.role in FLOOR_ROLES
            }
        }
        return when (path) {
            TOPIC_STAFF_ORDERS, TOPIC_STAFF_READY, TOPIC_TABLES -> principal.isStaffWithRole(FLOOR_ROLES)
            TOPIC_KITCHEN, TOPIC_KITCHEN_ORDERS -> principal.isStaffWithRole(KITCHEN_ROLES)
            TOPIC_MENU -> true
            else -> path.startsWith(USER_QUEUE_PREFIX)
        }
    }

    private fun AuthPrincipal.isStaffWithRole(roles: Set<UserRole>): Boolean =
        this is StaffPrincipal && role in roles

    companion object {
        const val TOPIC_STAFF_ORDERS = "/topic/staff/orders"
        const val TOPIC_STAFF_READY = "/topic/staff/ready"
        const val TOPIC_TABLES = "/topic/tables"
        const val TOPIC_KITCHEN = "/topic/kitchen"
        /** GĐ4: món mới vào bếp / đổi trạng thái / bị hủy (KDS). */
        const val TOPIC_KITCHEN_ORDERS = "/topic/kitchen/orders"
        const val TOPIC_MENU = "/topic/menu"
        const val USER_QUEUE_PREFIX = "/user/queue/"
        private val SESSION_TOPIC = Regex("^/topic/session/(\\d{1,18})$")
        private val FLOOR_ROLES = setOf(UserRole.STAFF, UserRole.MANAGER)
        private val KITCHEN_ROLES = setOf(UserRole.KITCHEN, UserRole.MANAGER)
    }
}
