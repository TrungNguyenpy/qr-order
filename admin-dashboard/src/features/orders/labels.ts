import type { BatchStatus, BillStatus, CancelRequestStatus, OrderItemStatus, OrderStatus, PaymentMethod, PaymentStatus, TransactionStatus } from "@/lib/api/types";

export type Tone = "neutral" | "ok" | "warn" | "danger" | "accent";

export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  OPEN: "Đang mở",
  LOCKED: "Đã khóa (chờ thanh toán)",
  PAID: "Đã thanh toán",
  VOIDED: "Đã hủy hóa đơn",
  WRITTEN_OFF: "Xóa nợ",
};
export const ORDER_STATUS_TONE: Record<OrderStatus, Tone> = { OPEN: "accent", LOCKED: "warn", PAID: "ok", VOIDED: "neutral", WRITTEN_OFF: "danger" };

export const BATCH_STATUS_LABEL: Record<BatchStatus, string> = {
  PENDING: "Chờ xác nhận",
  ACCEPTED: "Đã nhận",
  PARTIALLY_ACCEPTED: "Nhận một phần",
  REJECTED: "Bị từ chối",
  CANCELLED: "Đã hủy",
  COMPLETED: "Hoàn tất",
};
export const BATCH_STATUS_TONE: Record<BatchStatus, Tone> = {
  PENDING: "warn",
  ACCEPTED: "accent",
  PARTIALLY_ACCEPTED: "accent",
  REJECTED: "danger",
  CANCELLED: "neutral",
  COMPLETED: "ok",
};

export const ITEM_STATUS_LABEL: Record<OrderItemStatus, string> = {
  PENDING: "Chờ xác nhận",
  CONFIRMED: "Đã xác nhận",
  PREPARING: "Đang chế biến",
  READY: "Sẵn sàng",
  SERVED: "Đã phục vụ",
  CANCELLED: "Đã hủy",
  REJECTED: "Bị từ chối",
};
export const ITEM_STATUS_TONE: Record<OrderItemStatus, Tone> = {
  PENDING: "warn",
  CONFIRMED: "accent",
  PREPARING: "accent",
  READY: "ok",
  SERVED: "ok",
  CANCELLED: "neutral",
  REJECTED: "danger",
};

export const CANCEL_STATUS_LABEL: Record<CancelRequestStatus, string> = { PENDING: "Chờ duyệt", APPROVED: "Đã duyệt", DENIED: "Đã từ chối" };
export const CANCEL_STATUS_TONE: Record<CancelRequestStatus, Tone> = { PENDING: "warn", APPROVED: "ok", DENIED: "danger" };

export const BILL_STATUS_LABEL: Record<BillStatus, string> = {
  ACTIVE: "Hiệu lực",
  VOID_PENDING: "Chờ hoàn tiền để hủy",
  VOIDED: "Đã hủy",
  WRITTEN_OFF: "Xóa nợ",
};
export const BILL_STATUS_TONE: Record<BillStatus, Tone> = { ACTIVE: "ok", VOID_PENDING: "warn", VOIDED: "neutral", WRITTEN_OFF: "danger" };

export const PAYMENT_STATUS_LABEL: Record<PaymentStatus, string> = { UNPAID: "Chưa thanh toán", PARTIALLY_PAID: "Thanh toán một phần", PAID: "Đã thanh toán" };
export const TRANSACTION_STATUS_LABEL: Record<TransactionStatus, string> = { PENDING: "Đang chờ", SUCCESS: "Thành công", FAILED: "Thất bại" };
export const TRANSACTION_STATUS_TONE: Record<TransactionStatus, Tone> = { PENDING: "warn", SUCCESS: "ok", FAILED: "danger" };

export const PAYMENT_METHOD_LABEL: Record<PaymentMethod, string> = { CASH: "Tiền mặt", BANK_TRANSFER: "Chuyển khoản", CARD_POS: "Quẹt thẻ (POS)", OTHER: "Khác" };
export const PAYMENT_METHODS: PaymentMethod[] = ["CASH", "BANK_TRANSFER", "CARD_POS", "OTHER"];

export const VOID_TYPE_LABEL: Record<string, string> = {
  SESSION_REOPEN: "Mở lại phiên",
  MANAGER_VOID: "Quản lý hủy hóa đơn",
  FORCE_CLOSE_NO_CHARGE: "Đóng cưỡng chế, không thu tiền",
};

export const REASON_CODE_LABEL: Record<string, string> = {
  OUT_OF_STOCK: "Hết món",
  SESSION_FORCE_CLOSED: "Đóng phiên cưỡng chế",
};
