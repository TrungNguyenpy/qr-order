package com.qrorder.notification.websocket

import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.security.GuestPrincipal
import com.qrorder.auth.security.StaffPrincipal
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** Luật SUBSCRIBE theo SRS §11.4. */
class StompTopicAuthorizerTest {

    private val authorizer = StompTopicAuthorizer()
    private val guest = GuestPrincipal(sessionId = 42, deviceId = "device-0001")
    private fun staff(role: UserRole) = StaffPrincipal(1, role.name.lowercase(), role)

    @Test
    fun `guest only sees its own session topic and the menu`() {
        assertEquals(true, authorizer.canSubscribe(guest, "/topic/session/42"))
        assertEquals(false, authorizer.canSubscribe(guest, "/topic/session/43"))
        assertEquals(true, authorizer.canSubscribe(guest, "/topic/menu"))
        assertEquals(false, authorizer.canSubscribe(guest, "/topic/staff/orders"))
        assertEquals(false, authorizer.canSubscribe(guest, "/topic/kitchen"))
        assertEquals(false, authorizer.canSubscribe(guest, "/topic/kitchen/orders"))
    }

    @Test
    fun `operational topics follow role matrix and ADMIN is excluded`() {
        val matrix = mapOf(
            "/topic/staff/orders" to setOf(UserRole.STAFF, UserRole.MANAGER),
            "/topic/staff/ready" to setOf(UserRole.STAFF, UserRole.MANAGER),
            "/topic/tables" to setOf(UserRole.STAFF, UserRole.MANAGER),
            "/topic/kitchen" to setOf(UserRole.KITCHEN, UserRole.MANAGER),
            "/topic/kitchen/orders" to setOf(UserRole.KITCHEN, UserRole.MANAGER),
            "/topic/session/42" to setOf(UserRole.STAFF, UserRole.MANAGER),
            "/topic/menu" to UserRole.entries.toSet(),
        )
        matrix.forEach { (topic, allowed) ->
            UserRole.entries.forEach { role ->
                assertEquals(role in allowed, authorizer.canSubscribe(staff(role), topic), "$role -> $topic")
            }
        }
    }

    @Test
    fun `unknown, wildcard and traversal destinations are denied`() {
        listOf(null, "/topic/other", "/topic/session/*", "/topic/session/../staff/orders", "/topic/session/abc").forEach {
            assertEquals(false, authorizer.canSubscribe(staff(UserRole.MANAGER), it), "$it")
        }
        assertEquals(true, authorizer.canSubscribe(guest, "/user/queue/errors"))
    }
}
