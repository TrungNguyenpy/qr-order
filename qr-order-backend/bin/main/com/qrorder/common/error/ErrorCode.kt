package com.qrorder.common.error

import org.springframework.http.HttpStatus

/**
 * Danh mục mã lỗi nghiệp vụ thống nhất (hợp đồng API).
 *
 * Ba nguồn:
 * - [Source.APPLICATION]: lỗi do backend phát hiện (validation, xác thực, phân quyền, quy tắc ở service).
 * - [Source.DB_TRIGGER]: mã do trigger PostgreSQL RAISE EXCEPTION (SQLSTATE P0001), thông điệp dạng
 *   `MA_LOI: mô tả`. Tên enum TRÙNG KHỚP mã trong SQL để ánh xạ trực tiếp. Test
 *   `ErrorCodeCoverageTest` quét file migration và bắt buộc mọi mã trigger đều có ở đây.
 * - [Source.DB_CONSTRAINT]: vi phạm unique có tên (SQLSTATE 23505), ánh xạ ở [DatabaseErrorTranslator].
 *
 * Quy ước HTTP status cho mã trigger:
 * - 409 Conflict: xung đột trạng thái / máy trạng thái (client có thể tải lại dữ liệu rồi thử lại).
 * - 422 Unprocessable: vi phạm giới hạn tiền (BR-P10, BR-P13) do dữ liệu client gửi.
 * - 500: vi phạm bất biến mà service lẽ ra phải chặn trước (snapshot, append-only, rotation dở dang...).
 *   Trigger là hàng rào cuối (DATABASE_DESIGN.md §5, DB-08); gặp mã này nghĩa là có lỗi ở service.
 */
enum class ErrorCode(
    val httpStatus: HttpStatus,
    val defaultMessage: String,
    val source: Source = Source.APPLICATION,
) {
    // ---------------------------------------------------------------- Chung (APPLICATION)
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Yêu cầu không đúng định dạng"),
    INVALID_REASON_CODE(HttpStatus.BAD_REQUEST, "Mã lý do không hợp lệ hoặc bị thiếu"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Chưa đăng nhập"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token không hợp lệ"),
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "Tài khoản đã bị khóa hoặc ngừng sử dụng"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Không có quyền truy cập"),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "Thiếu quyền thực hiện thao tác này"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy dữ liệu"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Phương thức không được hỗ trợ"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Dữ liệu vừa được người khác cập nhật, vui lòng tải lại"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "Dữ liệu đã tồn tại"),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "Vi phạm quy tắc nghiệp vụ"),
    INVALID_REFERENCE(HttpStatus.UNPROCESSABLE_ENTITY, "Dữ liệu tham chiếu không tồn tại"),
    /** BR-S09 (GĐ3: join phiên 10 lần/phút/IP). Phản hồi kèm header Retry-After (giây). */
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Quá nhiều yêu cầu, vui lòng thử lại sau"),
    DATA_INTEGRITY_VIOLATION(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi toàn vẹn dữ liệu"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống"),

    // ---------------------------------------------------------------- Xác thực & quản trị tài khoản (Giai đoạn 1)
    /** Sai username hoặc mật khẩu; không phân biệt hai trường hợp để tránh dò tài khoản. */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Tên đăng nhập hoặc mật khẩu không đúng"),
    /** Refresh token không tồn tại (FR-AUTH-02). Client phải đăng nhập lại. */
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Refresh token không hợp lệ"),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Refresh token đã hết hạn, vui lòng đăng nhập lại"),
    /** Token đã bị dùng/thu hồi: cả family đã bị thu hồi (REUSE_DETECTED). */
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không còn hiệu lực, vui lòng đăng nhập lại"),
    /**
     * Xác thực lại (nhập lại mật khẩu hiện tại) thất bại - BR-A07, BR-A08, đổi mật khẩu.
     * Dùng 403 thay vì 401 để client không hiểu nhầm là access token hết hạn và tự refresh/đăng xuất.
     */
    REAUTHENTICATION_FAILED(HttpStatus.FORBIDDEN, "Mật khẩu hiện tại không đúng"),
    /** Thu hồi permission mà user không có bản cấp còn hiệu lực. */
    PERMISSION_NOT_GRANTED(HttpStatus.CONFLICT, "Người dùng chưa được cấp quyền này"),

    // ---------------------------------------------------------------- Ảnh món / Cloudinary (Giai đoạn 2)
    /** Ảnh không phải JPEG/PNG/WebP (kiểm tra cả Content-Type lẫn chữ ký nhị phân của file). */
    UNSUPPORTED_IMAGE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Ảnh phải ở định dạng JPEG, PNG hoặc WebP"),
    IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Ảnh vượt quá dung lượng cho phép"),
    /** Thiếu CLOUDINARY_* trong môi trường: không upload được nhưng các API menu khác vẫn chạy. */
    IMAGE_STORAGE_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình dịch vụ lưu trữ ảnh"),
    /** Cloudinary lỗi/không phản hồi; dữ liệu món không bị thay đổi. */
    IMAGE_UPLOAD_FAILED(HttpStatus.BAD_GATEWAY, "Tải ảnh lên dịch vụ lưu trữ thất bại, vui lòng thử lại"),

    // ---------------------------------------------------------------- Bàn, QR, phiên bàn (Giai đoạn 3)
    /** Không có QR ACTIVE khớp token (token lạ, đã xoay hoặc đã thu hồi). Không phân biệt các trường hợp để tránh dò mã. */
    QR_CODE_INVALID(HttpStatus.NOT_FOUND, "Mã QR không hợp lệ hoặc đã được thay mới, vui lòng quét mã QR đang dán trên bàn"),
    /** Xoay/thu hồi/tải ảnh khi bàn chưa có QR ACTIVE. */
    QR_CODE_NOT_ACTIVE(HttpStatus.CONFLICT, "Bàn chưa có mã QR đang hoạt động"),
    /** Thiếu QR_BASE_URL hoặc QR_TOKEN_SECRET: không sinh/in được QR; join bằng QR đã có vẫn chạy. */
    QR_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình sinh mã QR (QR_BASE_URL, QR_TOKEN_SECRET)"),
    /** QR có từ trước (seed dev, hoặc đổi QR_TOKEN_SECRET) nên không dựng lại được URL để in; cần xoay mã mới. */
    QR_TOKEN_NOT_REPRODUCIBLE(HttpStatus.CONFLICT, "Không tạo lại được ảnh cho mã QR này, vui lòng xoay mã QR mới để in"),
    /** BR-S08: bàn is_active = false không nhận join, không mở phiên. */
    TABLE_INACTIVE(HttpStatus.CONFLICT, "Bàn đang tạm ngưng phục vụ"),
    /** Bàn không ở AVAILABLE (đang dọn) và chưa có phiên hoạt động: chưa nhận khách mới (§9.5). */
    TABLE_NOT_READY(HttpStatus.CONFLICT, "Bàn chưa sẵn sàng đón khách mới, vui lòng liên hệ nhân viên"),
    /** BR-S08: chỉ ngừng sử dụng bàn khi bàn AVAILABLE và không có phiên hoạt động. */
    TABLE_IN_USE(HttpStatus.CONFLICT, "Bàn đang có khách hoặc chưa dọn xong, chỉ ngừng sử dụng được khi bàn trống"),
    /** Chuyển trạng thái bàn thủ công ngoài ma trận cho phép (§9.5: chỉ xác nhận dọn xong CLEANING -> AVAILABLE). */
    INVALID_TABLE_TRANSITION(HttpStatus.CONFLICT, "Chuyển trạng thái bàn không hợp lệ"),
    /** DEC-07 A: PIN bàn không đúng. */
    TABLE_PIN_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "Mã PIN bàn không đúng"),
    /** BE-05, BR-S03: Guest JWT của phiên đã CLOSED/EXPIRED/FORCE_CLOSED (hoặc không còn) bị từ chối ngay. */
    SESSION_CLOSED(HttpStatus.UNAUTHORIZED, "Phiên bàn đã kết thúc, vui lòng quét lại mã QR"),
    /**
     * Force-close/hết hạn bị chặn vì phiên còn Order chưa xử lý xong. GĐ3 chưa có module Order/Payment nên chưa
     * ghi nhận thất thu (BR-S06, BR-P15); GĐ4-GĐ6 thay bằng xử lý đầy đủ qua SessionForceCloseListener.
     */
    SESSION_HAS_UNSETTLED_ORDER(HttpStatus.CONFLICT, "Phiên còn order/món chưa xử lý, chưa thể đóng phiên"),

    // ---------------------------------------------------------------- Order & Kitchen (Giai đoạn 4)
    /** BR-S03/§9.7: phiên BILLING (hoặc đã đóng) không nhận đợt order mới. */
    SESSION_NOT_OPEN(HttpStatus.CONFLICT, "Phiên bàn không còn nhận order mới"),
    /** BE-23: require_table_pin bật, phiên có PIN nhưng thiết bị chưa nhập đúng PIN. */
    TABLE_PIN_REQUIRED(HttpStatus.FORBIDDEN, "Cần nhập mã PIN bàn trước khi đặt món"),
    /** BR-O02: món SOLD_OUT / HIDDEN / thuộc danh mục ẩn không đặt được. */
    PRODUCT_NOT_AVAILABLE(HttpStatus.CONFLICT, "Món hiện không phục vụ"),
    /** BR-O02, BR-O03: lựa chọn option không thuộc món, thiếu nhóm bắt buộc, vượt số lượng chọn tối đa hoặc trùng. */
    INVALID_OPTION_SELECTION(HttpStatus.UNPROCESSABLE_ENTITY, "Lựa chọn tùy chọn của món không hợp lệ"),
    /** BR-S09: số đợt PENDING của phiên đạt max_pending_batches_per_session. */
    MAX_PENDING_BATCHES_REACHED(HttpStatus.CONFLICT, "Còn quá nhiều đợt order đang chờ xác nhận, vui lòng đợi nhân viên xử lý"),
    BATCH_NOT_PENDING(HttpStatus.CONFLICT, "Đợt order không còn ở trạng thái chờ xác nhận"),
    /** BR-O10: đợt đang được người khác giữ claim (còn hạn). */
    BATCH_CLAIMED_BY_OTHER(HttpStatus.CONFLICT, "Đợt order đang được nhân viên khác xử lý"),
    /** Món không ở trạng thái cho phép thao tác này (hủy khi đã nấu, phục vụ khi chưa sẵn sàng...). */
    ORDER_ITEM_STATE_INVALID(HttpStatus.CONFLICT, "Món không ở trạng thái cho phép thao tác này"),
    /** BR-O06: yêu cầu hủy chỉ cho món PREPARING/READY. */
    CANCEL_REQUEST_NOT_ALLOWED(HttpStatus.CONFLICT, "Món không thể gửi yêu cầu hủy ở trạng thái hiện tại"),
    CANCEL_REQUEST_NOT_PENDING(HttpStatus.CONFLICT, "Yêu cầu hủy đã được xử lý"),

    // ---------------------------------------------------------------- Billing, Payment, ServiceCall (Giai đoạn 5a)
    /** BR-P05: Order không có món tính tiền nào (CONFIRMED, PREPARING, READY, SERVED) thì không tạo Bill. */
    NO_BILLABLE_ITEMS(HttpStatus.CONFLICT, "Order chưa có món tính tiền, không thể tạo Bill"),
    /** Thao tác chỉ dành cho Bill ACTIVE (giảm giá, mở lại phiên...). */
    BILL_NOT_ACTIVE(HttpStatus.CONFLICT, "Bill không còn ở trạng thái hiện hành"),
    /** BR-P08 (a), BR-P07: Bill đã có Payment SUCCESS hoặc PENDING thì không phát hành lại / mở lại phiên bởi Staff. */
    BILL_HAS_PAYMENTS(HttpStatus.CONFLICT, "Bill đã có giao dịch thanh toán thành công hoặc đang chờ, không thể thực hiện thao tác này"),
    /** BR-S06 (i): Bill đang chờ hoàn tiền (VOID_PENDING) thì không force-close / không mở lại phiên. */
    BILL_VOID_PENDING(HttpStatus.CONFLICT, "Bill đang chờ hoàn tiền (Void), cần hoàn tất hoặc rút yêu cầu Void trước"),
    /** BR-P07: giảm giá không hợp lệ (phần trăm ngoài (0, 100], số tiền vượt Subtotal, kết quả bằng 0 đồng...). */
    INVALID_DISCOUNT(HttpStatus.UNPROCESSABLE_ENTITY, "Giảm giá không hợp lệ"),
    /** FR-PAY-12: chỉ giao dịch SUCCESS mới có phiếu thanh toán (BR-P12). */
    RECEIPT_NOT_AVAILABLE(HttpStatus.CONFLICT, "Giao dịch chưa thành công nên chưa có phiếu thanh toán"),
    /** BR-P08 (a): chỉ mở lại phiên đang BILLING. */
    SESSION_NOT_BILLING(HttpStatus.CONFLICT, "Phiên bàn không ở trạng thái đang tính tiền"),
    /** §9.8: chuyển trạng thái ServiceCall không hợp lệ (đã RESOLVED, loại yêu cầu không phù hợp...). */
    SERVICE_CALL_STATE_INVALID(HttpStatus.CONFLICT, "Yêu cầu hỗ trợ không ở trạng thái cho phép thao tác này"),

    // ---------------------------------------------------------------- Void, Refund, Bill thay thế (Giai đoạn 5b)
    /** BR-P14: rút Void / hoàn tiền chỉ cho Bill VOID_PENDING. */
    BILL_NOT_VOID_PENDING(HttpStatus.CONFLICT, "Bill không ở trạng thái chờ hoàn tiền (Void)"),
    /** BR-P14, SRS §12.3 #4: đã có Refund PENDING hoặc SUCCESS thì không rút Void, phải hoàn tiếp. */
    VOID_HAS_REFUNDS(HttpStatus.CONFLICT, "Đã có giao dịch hoàn tiền đang chờ hoặc thành công, không thể rút yêu cầu Void"),
    /** BR-P16: Bill thay thế chỉ khi Order LOCKED chờ Bill thay thế và Bill gần nhất đã VOIDED. */
    REPLACEMENT_BILL_NOT_ALLOWED(HttpStatus.CONFLICT, "Order không ở trạng thái chờ Bill thay thế"),

    // ---------------------------------------------------------------- Mã nghiệp vụ đặt tên trong SRS
    /** BR-P03: chưa cấu hình vat_rate thì chặn tạo Bill (SRS §6.8: 422). */
    VAT_NOT_CONFIGURED(HttpStatus.UNPROCESSABLE_ENTITY, "Chưa cấu hình thuế suất VAT, không thể tạo Bill"),
    /** BR-O11: còn đợt PENDING thì không tạo Bill (SRS §6.8: 409). */
    BATCH_PENDING(HttpStatus.CONFLICT, "Còn đợt order chờ xác nhận, cần xử lý trước khi tính tiền"),

    // ---------------------------------------------------------------- Vi phạm unique có tên (DB_CONSTRAINT)
    USERNAME_TAKEN(HttpStatus.CONFLICT, "Tên đăng nhập đã tồn tại", Source.DB_CONSTRAINT),
    CATEGORY_NAME_TAKEN(HttpStatus.CONFLICT, "Tên danh mục đã tồn tại", Source.DB_CONSTRAINT),
    OPTION_ITEM_NAME_TAKEN(HttpStatus.CONFLICT, "Tên lựa chọn đã tồn tại trong nhóm", Source.DB_CONSTRAINT),
    TABLE_CODE_TAKEN(HttpStatus.CONFLICT, "Mã bàn đã tồn tại", Source.DB_CONSTRAINT),
    QR_ALREADY_ACTIVE(HttpStatus.CONFLICT, "Bàn đã có mã QR đang hoạt động", Source.DB_CONSTRAINT),
    SESSION_ALREADY_ACTIVE(HttpStatus.CONFLICT, "Bàn đã có phiên đang hoạt động", Source.DB_CONSTRAINT),
    ORDER_ALREADY_EXISTS(HttpStatus.CONFLICT, "Phiên đã có Order", Source.DB_CONSTRAINT),
    DUPLICATE_CLIENT_REQUEST(HttpStatus.CONFLICT, "Đợt order này đã được gửi", Source.DB_CONSTRAINT),
    DUPLICATE_REQUEST_ID(HttpStatus.CONFLICT, "Giao dịch này đã được ghi nhận", Source.DB_CONSTRAINT),
    /** GĐ5a: (provider, provider_txn_id) đã được dùng cho giao dịch thanh toán khác (ux_payment_transaction_provider_txn). */
    DUPLICATE_PROVIDER_TXN(HttpStatus.CONFLICT, "Mã giao dịch của nhà cung cấp đã được ghi nhận cho giao dịch khác", Source.DB_CONSTRAINT),
    BILL_ALREADY_ACTIVE(HttpStatus.CONFLICT, "Order đã có Bill hiện hành", Source.DB_CONSTRAINT),
    SERVICE_CALL_ALREADY_OPEN(HttpStatus.CONFLICT, "Yêu cầu cùng loại đang được xử lý", Source.DB_CONSTRAINT),
    PERMISSION_ALREADY_GRANTED(HttpStatus.CONFLICT, "Người dùng đã có quyền này", Source.DB_CONSTRAINT),
    CANCEL_REQUEST_ALREADY_PENDING(HttpStatus.CONFLICT, "Món đã có yêu cầu hủy đang chờ duyệt", Source.DB_CONSTRAINT),

    // ---------------------------------------------------------------- Mã trigger PostgreSQL (DB_TRIGGER)
    // 409 - xung đột trạng thái
    LAST_ACTIVE_ADMIN(HttpStatus.CONFLICT, "Không thể hạ quyền, khóa hoặc ngừng dùng ADMIN hoạt động cuối cùng", Source.DB_TRIGGER),
    PERMISSION_ALREADY_REVOKED(HttpStatus.CONFLICT, "Quyền đã được thu hồi trước đó", Source.DB_TRIGGER),
    SESSION_FINAL_STATE(HttpStatus.CONFLICT, "Phiên bàn đã kết thúc", Source.DB_TRIGGER),
    INVALID_SESSION_TRANSITION(HttpStatus.CONFLICT, "Chuyển trạng thái phiên bàn không hợp lệ", Source.DB_TRIGGER),
    ORDER_FINAL_STATE(HttpStatus.CONFLICT, "Order đã ở trạng thái cuối", Source.DB_TRIGGER),
    INVALID_ORDER_TRANSITION(HttpStatus.CONFLICT, "Chuyển trạng thái Order không hợp lệ", Source.DB_TRIGGER),
    ORDER_NOT_OPEN(HttpStatus.CONFLICT, "Order đang thanh toán hoặc đã đóng, không thể hủy, từ chối hay sửa món", Source.DB_TRIGGER),
    ORDER_ITEM_NOT_EDITABLE(HttpStatus.CONFLICT, "Món đã vào chế biến, không thể sửa", Source.DB_TRIGGER),
    INVALID_ORDER_ITEM_TRANSITION(HttpStatus.CONFLICT, "Chuyển trạng thái món không hợp lệ", Source.DB_TRIGGER),
    BILL_FINAL_STATE(HttpStatus.CONFLICT, "Bill đã ở trạng thái cuối", Source.DB_TRIGGER),
    INVALID_BILL_TRANSITION(HttpStatus.CONFLICT, "Chuyển trạng thái Bill không hợp lệ", Source.DB_TRIGGER),
    TRANSACTION_IMMUTABLE(HttpStatus.CONFLICT, "Giao dịch đã có kết quả, không thể thay đổi", Source.DB_TRIGGER),
    PAYMENT_BILL_NOT_ACTIVE(HttpStatus.CONFLICT, "Bill không ở trạng thái nhận thanh toán", Source.DB_TRIGGER),
    REFUND_REQUIRES_VOID_PENDING(HttpStatus.CONFLICT, "Chỉ hoàn tiền cho Bill đang chờ Void", Source.DB_TRIGGER),
    REFUND_ONLY_FOR_VOID(HttpStatus.CONFLICT, "Bill có hoàn tiền phải giữ trạng thái Void", Source.DB_TRIGGER),
    PENDING_PAYMENT_EXISTS(HttpStatus.CONFLICT, "Bill còn giao dịch thanh toán đang chờ", Source.DB_TRIGGER),
    VOID_NOT_COMPLETE(HttpStatus.CONFLICT, "Chưa hoàn đủ tiền, chưa thể hoàn tất Void", Source.DB_TRIGGER),
    REFRESH_TOKEN_ALREADY_REVOKED(HttpStatus.CONFLICT, "Refresh token đã bị thu hồi", Source.DB_TRIGGER),

    // 422 - vượt giới hạn tiền
    PAYMENT_EXCEEDS_REMAINING(HttpStatus.UNPROCESSABLE_ENTITY, "Số tiền thu vượt quá số còn phải thu của Bill", Source.DB_TRIGGER),
    REFUND_EXCEEDS_REFUNDABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Số tiền hoàn vượt quá số còn được hoàn", Source.DB_TRIGGER),

    // 500 - vi phạm bất biến (lỗi service)
    HARD_DELETE_FORBIDDEN(HttpStatus.INTERNAL_SERVER_ERROR, "Không được xóa cứng dữ liệu nghiệp vụ", Source.DB_TRIGGER),
    APPEND_ONLY(HttpStatus.INTERNAL_SERVER_ERROR, "Dữ liệu lịch sử chỉ được thêm mới", Source.DB_TRIGGER),
    PERMISSION_GRANT_IMMUTABLE(HttpStatus.INTERNAL_SERVER_ERROR, "Bản cấp quyền không được sửa", Source.DB_TRIGGER),
    SEQUENCE_NOT_MONOTONIC(HttpStatus.INTERNAL_SERVER_ERROR, "Dãy số chứng từ không hợp lệ", Source.DB_TRIGGER),
    INVALID_INITIAL_STATUS(HttpStatus.INTERNAL_SERVER_ERROR, "Trạng thái khởi tạo không hợp lệ", Source.DB_TRIGGER),
    SESSION_IMMUTABLE(HttpStatus.INTERNAL_SERVER_ERROR, "Không được đổi bàn hoặc thời điểm mở của phiên", Source.DB_TRIGGER),
    ORDER_IMMUTABLE(HttpStatus.INTERNAL_SERVER_ERROR, "Không được đổi phiên của Order", Source.DB_TRIGGER),
    ORDER_ITEM_SNAPSHOT_IMMUTABLE(HttpStatus.INTERNAL_SERVER_ERROR, "Thông tin snapshot của món không được sửa", Source.DB_TRIGGER),
    ORDER_ITEM_WASTED_REQUIRED(HttpStatus.INTERNAL_SERVER_ERROR, "Món đã chế biến bị hủy/từ chối phải ghi nhận wasted", Source.DB_TRIGGER),
    BILL_SNAPSHOT_IMMUTABLE(HttpStatus.INTERNAL_SERVER_ERROR, "Số liệu Bill không được sửa tại chỗ", Source.DB_TRIGGER),
    BILL_PAYMENT_STATUS_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "Trạng thái thanh toán của Bill không khớp số tiền đã thu", Source.DB_TRIGGER),
    REFRESH_TOKEN_IMMUTABLE(HttpStatus.INTERNAL_SERVER_ERROR, "Refresh token không được sửa", Source.DB_TRIGGER),
    REFRESH_TOKEN_ROTATION_INCOMPLETE(HttpStatus.INTERNAL_SERVER_ERROR, "Xoay vòng refresh token chưa hoàn tất", Source.DB_TRIGGER),
    ;

    enum class Source { APPLICATION, DB_TRIGGER, DB_CONSTRAINT }

    companion object {
        private val triggerCodes: Map<String, ErrorCode> =
            entries.filter { it.source == Source.DB_TRIGGER }.associateBy { it.name }

        /** Tra mã trigger (phần trước dấu `:` trong thông điệp RAISE EXCEPTION). */
        fun fromTriggerCode(code: String?): ErrorCode? = code?.let { triggerCodes[it] }
    }
}
