package com.qrorder.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Đồng hồ dùng chung (UTC). Service nhận Clock qua constructor để test điều khiển được thời gian.
 * Ngày nghiệp vụ (mã chứng từ, báo cáo) luôn tính theo Asia/Ho_Chi_Minh (BR-P01) - xem [BusinessTime].
 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

object BusinessTime {
    /** Múi giờ nghiệp vụ cố định ở giai đoạn 1 (restaurant_settings.timezone bị khóa bằng CHECK). */
    val ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Ho_Chi_Minh")
}
