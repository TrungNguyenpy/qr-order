import { ApiError } from "./api/client";

/** Thông điệp thân thiện cho các mã lỗi nghiệp vụ đã biết; mặc định dùng message của backend. */
const MESSAGES: Record<string, string> = {
  QR_CODE_INVALID: "Mã QR không hợp lệ hoặc đã bị thay thế. Vui lòng quét lại mã trên bàn.",
  TABLE_INACTIVE: "Bàn này hiện không hoạt động. Vui lòng liên hệ nhân viên.",
  TABLE_NOT_READY: "Bàn chưa sẵn sàng phục vụ. Vui lòng liên hệ nhân viên.",
  TABLE_PIN_INVALID: "Mã PIN không đúng. Vui lòng thử lại.",
  TABLE_PIN_REQUIRED: "Vui lòng nhập mã PIN của bàn trước khi gọi món.",
  SESSION_NOT_OPEN: "Bàn đang chờ thanh toán nên không thể gọi thêm món.",
  PRODUCT_NOT_AVAILABLE: "Có món vừa hết. Vui lòng kiểm tra lại giỏ hàng.",
  INVALID_OPTION_SELECTION: "Lựa chọn tùy chọn không hợp lệ. Vui lòng kiểm tra lại món.",
  MAX_PENDING_BATCHES_REACHED: "Bạn đang có quá nhiều đợt chờ xác nhận. Vui lòng đợi nhân viên xác nhận rồi gọi tiếp.",
  RATE_LIMIT_EXCEEDED: "Bạn thao tác quá nhanh. Vui lòng thử lại sau ít giây.",
  NETWORK_ERROR: "Không thể kết nối tới máy chủ. Vui lòng kiểm tra mạng và thử lại.",
};

export function friendlyError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === "RATE_LIMIT_EXCEEDED" && e.retryAfter) {
      return `Bạn thao tác quá nhanh. Vui lòng thử lại sau ${e.retryAfter} giây.`;
    }
    return MESSAGES[e.code] ?? e.message;
  }
  return "Đã có lỗi xảy ra. Vui lòng thử lại.";
}
