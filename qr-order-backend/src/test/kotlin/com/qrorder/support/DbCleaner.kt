package com.qrorder.support

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * Dọn dữ liệu phát sinh trong test, giữ nguyên seed dev (V2: bàn, QR, menu, cấu hình).
 *
 * Trigger chặn xóa cứng (BR-A03) và FK không cho DELETE/TRUNCATE trực tiếp các bảng nghiệp vụ, nên
 * trong transaction dọn dẹp tạm đặt `session_replication_role = replica` (vô hiệu trigger kể cả trigger
 * FK; cần quyền superuser - user mặc định của container postgres có quyền này). SET LOCAL tự hết hiệu
 * lực khi transaction kết thúc. CHỈ DÙNG TRONG TEST.
 */
object DbCleaner {
    private val RUNTIME_TABLES = listOf(
        "refresh_token",
        "user_permission",
        "audit_log",
        "transaction_status_history",
        "refund_transaction",
        "payment_transaction",
        "bill",
        "order_item_status_history",
        "order_item_option",
        "item_cancel_request",
        "order_item",
        "order_batch",
        "orders",
        "service_call",
        "session_device",
        "table_session",
        "daily_sequence",
        "users",
    )

    /**
     * Bảng có seed V2 mà test được phép sửa: menu (Giai đoạn 2) và bàn + QR (Giai đoạn 3: tạo/sửa bàn, ngừng dùng,
     * xoay/thu hồi QR). Phải trả về đúng seed V2 sau mỗi test.
     * Lần dọn đầu tiên trên mỗi database chụp nguyên trạng seed vào schema riêng `test_seed` (không nằm trong
     * `public` nên không ảnh hưởng kiểm tra schema/Hibernate validate); các lần sau xóa và chép lại từ bản chụp.
     * Thứ tự: bảng con trước (chỉ để dễ đọc - FK đã tạm tắt bằng replica).
     */
    private val SEED_TABLES = listOf(
        "product_option_group", "option_item", "option_group", "product", "category",
        "qr_code", "dining_table",
    )

    fun clean(jdbc: JdbcTemplate, tx: TransactionTemplate) {
        tx.executeWithoutResult {
            jdbc.execute("SET LOCAL session_replication_role = replica")
            RUNTIME_TABLES.forEach { jdbc.execute("DELETE FROM $it") }
            restoreSeed(jdbc)
            // Giai đoạn 1: test sửa restaurant_settings -> trả về đúng giá trị V1 + seed V2 (VAT 8%, version 1).
            jdbc.execute(
                """UPDATE restaurant_settings SET vat_rate = 0.0800, service_fee_rate = 0, service_fee_taxable = NULL,
                       session_idle_timeout_minutes = 180, guest_jwt_ttl_minutes = 15, batch_claim_ttl_seconds = 60,
                       max_pending_batches_per_session = 3, join_rate_limit_per_minute = 10,
                       order_rate_limit_per_minute = 5, max_item_quantity = 99,
                       order_confirmation_mode = 'FIRST_BATCH_ONLY', require_table_pin = false,
                       cleaning_auto_release = false, bank_bin = NULL, bank_account_no = NULL,
                       bank_account_name = NULL, updated_by = NULL, version = 1
                 WHERE id = 1""",
            )
        }
    }

    private fun restoreSeed(jdbc: JdbcTemplate) {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS $SEED_SCHEMA")
        SEED_TABLES.forEach { table ->
            // Lần đầu (bảng chụp chưa có): chụp seed hiện tại. CREATE TABLE IF NOT EXISTS ... AS không chạy lại SELECT.
            jdbc.execute("CREATE TABLE IF NOT EXISTS $SEED_SCHEMA.$table AS TABLE public.$table")
        }
        SEED_TABLES.forEach { jdbc.execute("DELETE FROM public.$it") }
        // Cha trước con khi chèn lại (giữ nguyên id, version, thời điểm của seed).
        SEED_TABLES.reversed().forEach { jdbc.execute("INSERT INTO public.$it SELECT * FROM $SEED_SCHEMA.$it") }
    }

    private const val SEED_SCHEMA = "test_seed"
}
