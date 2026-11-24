import type { QrCodeStatus, SessionStatus, TableStatus } from "@/lib/api/types";

export const TABLE_STATUS_LABEL: Record<TableStatus, string> = {
  AVAILABLE: "Trống",
  OCCUPIED: "Đang phục vụ",
  PAYMENT_PENDING: "Chờ thanh toán",
  CLEANING: "Đang dọn",
};
export const TABLE_STATUS_TONE = { AVAILABLE: "ok", OCCUPIED: "accent", PAYMENT_PENDING: "warn", CLEANING: "neutral" } as const;
export const TABLE_STATUSES: TableStatus[] = ["AVAILABLE", "OCCUPIED", "PAYMENT_PENDING", "CLEANING"];

export const SESSION_STATUS_LABEL: Record<SessionStatus, string> = {
  OPEN: "Đang mở",
  BILLING: "Đang tính tiền",
  CLOSED: "Đã đóng",
  EXPIRED: "Hết hạn",
  FORCE_CLOSED: "Đóng cưỡng chế",
};

export const QR_STATUS_LABEL: Record<QrCodeStatus, string> = {
  ACTIVE: "Đang dùng",
  ROTATED: "Đã thay mã",
  REVOKED: "Đã thu hồi",
};
export const QR_STATUS_TONE = { ACTIVE: "ok", ROTATED: "neutral", REVOKED: "danger" } as const;
