package com.qrorder

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * QR Order backend - modular monolith (SRS §11.1).
 *
 * Package theo module nghiệp vụ:
 * - `common`       : response wrapper, mã lỗi, xử lý lỗi, cấu hình dùng chung, settings, audit, dãy số chứng từ
 * - `auth`         : user, permission, refresh token, Spring Security + JWT (Authentication, User Management)
 * - `menu`         : danh mục, món, nhóm option (Category, Product)
 * - `table`        : bàn, QR, phiên bàn, thiết bị (Table, QR Code, Table Session)
 * - `order`        : order, đợt, món, option, yêu cầu hủy, lịch sử món (Order, Kitchen)
 * - `billing`      : bill, thanh toán, hoàn tiền, lịch sử giao dịch (Payment)
 * - `notification` : ServiceCall, WebSocket STOMP (Notification)
 * - `report`       : chỉ đọc (Report & Analytics) - bắt đầu ở Giai đoạn 7
 */
// Không dùng InMemoryUserDetailsManager mặc định (Boot sẽ sinh và log mật khẩu ngẫu nhiên);
// xác thực nhân viên dựa trên bảng users (Giai đoạn 1).
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
class QrOrderApplication

fun main(args: Array<String>) {
    runApplication<QrOrderApplication>(*args)
}
