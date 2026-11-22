package com.qrorder.notification.websocket

import com.qrorder.auth.security.AuthPrincipal
import com.qrorder.auth.security.AuthPrincipalResolver
import com.qrorder.auth.security.AuthTokenException
import com.qrorder.auth.security.toAuthentication
import org.slf4j.LoggerFactory
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageDeliveryException
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

/**
 * Xác thực và phân quyền STOMP:
 * - CONNECT: bắt buộc header `Authorization: Bearer <JWT>` (Staff hoặc Guest). Staff bị khóa/đổi role
 *   bị từ chối ngay (BR-A04). Thành công thì gắn principal vào phiên WebSocket.
 * - SUBSCRIBE: kiểm tra theo [StompTopicAuthorizer].
 * - SEND: chỉ client đã CONNECT thành công.
 * Lỗi -> server gửi frame ERROR và đóng kết nối.
 */
@Component
class StompAuthChannelInterceptor(
    private val principalResolver: AuthPrincipalResolver,
    private val topicAuthorizer: StompTopicAuthorizer,
) : ChannelInterceptor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java) ?: return message
        when (accessor.command) {
            StompCommand.CONNECT -> accessor.user = authenticate(accessor)
            StompCommand.SUBSCRIBE -> {
                val principal = principalOf(accessor)
                if (!topicAuthorizer.canSubscribe(principal, accessor.destination)) {
                    log.info("Từ chối SUBSCRIBE {} của {}", accessor.destination, principal.name)
                    throw AccessDeniedException("Không có quyền subscribe ${accessor.destination}")
                }
            }
            StompCommand.SEND -> principalOf(accessor)
            else -> Unit
        }
        return message
    }

    private fun authenticate(accessor: StompHeaderAccessor): Authentication {
        val header = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER)
            ?: throw MessageDeliveryException("Thiếu header Authorization khi CONNECT")
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            throw MessageDeliveryException("Header Authorization phải có dạng Bearer <token>")
        }
        return try {
            principalResolver.resolve(header.substring(BEARER_PREFIX.length).trim()).toAuthentication()
        } catch (ex: AuthTokenException) {
            throw MessageDeliveryException("Xác thực WebSocket thất bại: ${ex.errorCode}")
        }
    }

    private fun principalOf(accessor: StompHeaderAccessor): AuthPrincipal =
        (accessor.user as? Authentication)?.principal as? AuthPrincipal
            ?: throw AccessDeniedException("Phiên WebSocket chưa xác thực")

    companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
