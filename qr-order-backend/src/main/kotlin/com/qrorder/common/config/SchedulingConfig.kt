package com.qrorder.common.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskRegistrar

/**
 * Bật tác vụ định kỳ (Giai đoạn 3: quét phiên hết hạn - BR-S07, UC36).
 *
 * Dùng thread pool riêng (`qr-scheduler-*`) thay vì TaskScheduler của STOMP broker (heartbeat WebSocket) để một lượt
 * quét dài không làm trễ heartbeat. Tắt bằng `app.scheduling.enabled=false` (profile test tắt để integration test gọi
 * trực tiếp tác vụ, kết quả không phụ thuộc thời điểm Scheduler chạy). Kiến trúc một instance (DEC-15) nên không cần
 * khóa phân tán.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SchedulingConfig : SchedulingConfigurer {
    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        val scheduler = ThreadPoolTaskScheduler().apply {
            poolSize = POOL_SIZE
            setThreadNamePrefix("qr-scheduler-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS)
            initialize()
        }
        registrar.setTaskScheduler(scheduler)
    }

    companion object {
        private const val POOL_SIZE = 2
        private const val AWAIT_TERMINATION_SECONDS = 30
    }
}
