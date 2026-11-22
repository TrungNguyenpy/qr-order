package com.qrorder.notification.websocket

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@ConfigurationProperties(prefix = "app.websocket")
data class WebSocketProperties(
    val allowedOrigins: List<String> = emptyList(),
    val heartbeatMs: Long = 10_000,
)

/**
 * WebSocket STOMP (SRS §6.1, §11.4; DEC-15: Simple Broker, một instance ở giai đoạn 1).
 *
 * - Endpoint `/ws` hỗ trợ SockJS (Customer Web/Admin) và WebSocket thuần tại `/ws/websocket` (Staff App Flutter).
 * - Broker: `/topic` (phát chung), `/queue` (riêng từng user qua `/user/queue/...`); gửi lên server qua `/app`.
 * - Heartbeat 2 chiều để phát hiện mất kết nối (NFR-07); client resync qua REST khi reconnect (FR-NOT-02).
 * - Xác thực/phân quyền: [StompAuthChannelInterceptor].
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val properties: WebSocketProperties,
    private val stompAuthChannelInterceptor: StompAuthChannelInterceptor,
) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val origins = properties.allowedOrigins.map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        registry.addEndpoint(ENDPOINT)
            .setAllowedOriginPatterns(*origins)
            .withSockJS()
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        val heartbeatScheduler = ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("ws-heartbeat-")
            isDaemon = true
            initialize()
        }
        registry.enableSimpleBroker("/topic", "/queue")
            .setHeartbeatValue(longArrayOf(properties.heartbeatMs, properties.heartbeatMs))
            .setTaskScheduler(heartbeatScheduler)
        registry.setApplicationDestinationPrefixes("/app")
        registry.setUserDestinationPrefix("/user")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(stompAuthChannelInterceptor)
    }

    companion object {
        const val ENDPOINT = "/ws"
    }
}
