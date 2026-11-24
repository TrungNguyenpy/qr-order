import type { Permission, UserRole, UserStatus } from "@/lib/api/types";

export const ROLE_LABEL: Record<UserRole, string> = {
  ADMIN: "Quản trị viên",
  MANAGER: "Quản lý",
  STAFF: "Nhân viên phục vụ",
  KITCHEN: "Bếp",
};

export const STATUS_LABEL: Record<UserStatus, string> = {
  ACTIVE: "Hoạt động",
  LOCKED: "Đã khóa",
  DISABLED: "Ngừng dùng",
};

export const PERMISSION_LABEL: Record<Permission, string> = {
  PAYMENT_PROCESS: "Tính tiền & thanh toán",
  BILL_DISCOUNT: "Giảm giá hóa đơn",
  BILL_VOID: "Void hóa đơn",
  REFUND_RECORD: "Ghi nhận hoàn tiền",
  ITEM_CANCEL_APPROVE: "Duyệt yêu cầu hủy món",
  SESSION_FORCE_CLOSE: "Force-close phiên bàn",
  SESSION_REOPEN: "Mở lại phiên bàn",
  REPORT_VIEW: "Xem báo cáo",
};

export const ROLES: UserRole[] = ["ADMIN", "MANAGER", "STAFF", "KITCHEN"];
export const STATUSES: UserStatus[] = ["ACTIVE", "LOCKED", "DISABLED"];
