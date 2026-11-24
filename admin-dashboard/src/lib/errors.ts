import { ApiError } from "./api/client";

/** Thông điệp thân thiện cho các mã lỗi đã biết; mặc định dùng message của backend. */
const MESSAGES: Record<string, string> = {
  NETWORK_ERROR: "Không thể kết nối tới máy chủ. Vui lòng kiểm tra mạng và thử lại.",
  INVALID_CREDENTIALS: "Tên đăng nhập hoặc mật khẩu không đúng.",
  ACCOUNT_DISABLED: "Tài khoản đã bị khóa hoặc ngừng sử dụng.",
  REAUTHENTICATION_FAILED: "Mật khẩu hiện tại không đúng.",
  CONCURRENT_MODIFICATION: "Dữ liệu vừa được người khác cập nhật. Vui lòng tải lại rồi thao tác lại.",
  LAST_ACTIVE_ADMIN: "Không thể thực hiện vì hệ thống phải luôn còn ít nhất một ADMIN đang hoạt động.",
  TABLE_IN_USE: "Bàn đang có khách hoặc chưa dọn xong. Chỉ ngừng sử dụng được khi bàn trống.",
  QR_NOT_CONFIGURED: "Backend chưa cấu hình sinh QR (QR_BASE_URL, QR_TOKEN_SECRET).",
  QR_TOKEN_NOT_REPRODUCIBLE: "Không dựng lại được ảnh cho mã QR này. Hãy xoay mã QR mới để in.",
  IMAGE_STORAGE_NOT_CONFIGURED: "Backend chưa cấu hình Cloudinary nên chưa tải ảnh lên được.",
  PERMISSION_DENIED: "Bạn không có quyền thực hiện thao tác này.",
  ACCESS_DENIED: "Bạn không có quyền truy cập chức năng này.",
};

export function friendlyError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === "RATE_LIMIT_EXCEEDED" && e.retryAfter) return `Thao tác quá nhanh. Thử lại sau ${e.retryAfter} giây.`;
    return MESSAGES[e.code] ?? e.message;
  }
  return "Đã có lỗi xảy ra. Vui lòng thử lại.";
}

export interface ErrorInfo {
  code: string;
  message: string;
  /** Lỗi validation theo từng trường (field -> message). */
  fields: Record<string, string>;
  /** 409 CONCURRENT_MODIFICATION: form đang giữ `version` cũ. */
  conflict: boolean;
  /** 403 REAUTHENTICATION_FAILED: hiển thị ngay trên ô mật khẩu, không đăng xuất. */
  reauthFailed: boolean;
}

export function describeError(e: unknown): ErrorInfo {
  const fields: Record<string, string> = {};
  if (e instanceof ApiError) {
    for (const d of e.details) if (d.field && !fields[d.field]) fields[d.field] = d.message;
    return {
      code: e.code,
      message: friendlyError(e),
      fields,
      conflict: e.code === "CONCURRENT_MODIFICATION",
      reauthFailed: e.code === "REAUTHENTICATION_FAILED",
    };
  }
  return { code: "UNKNOWN", message: friendlyError(e), fields, conflict: false, reauthFailed: false };
}
