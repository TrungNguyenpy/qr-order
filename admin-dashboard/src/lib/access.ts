import type { CurrentUser, Permission, UserRole } from "./api/types";

/** Quy tắc truy cập một chức năng: role (OR) và/hoặc permission (ADMIN không mặc định kế thừa quyền MANAGER, BR-A06). */
export interface AccessRule {
  roles?: UserRole[];
  permission?: Permission;
}

/** Chỉ ADMIN và MANAGER dùng Admin Dashboard; STAFF/KITCHEN dùng Staff App. */
export const DASHBOARD_ROLES: UserRole[] = ["ADMIN", "MANAGER"];

export function canUseDashboard(user: CurrentUser | null): boolean {
  return !!user && user.status === "ACTIVE" && DASHBOARD_ROLES.includes(user.role);
}

/** Cả `roles` và `permission` (nếu có) phải thỏa. `permissions` của /auth/me đã là permission hiệu lực. */
export function canAccess(user: CurrentUser | null, rule: AccessRule): boolean {
  if (!user || user.status !== "ACTIVE") return false;
  if (rule.roles && !rule.roles.includes(user.role)) return false;
  if (rule.permission && !user.permissions.includes(rule.permission)) return false;
  return true;
}

export const ACCESS = {
  employees: { roles: ["ADMIN"] },
  settings: { roles: ["ADMIN"] },
  menu: { roles: ["ADMIN", "MANAGER"] },
  tablesView: { roles: ["ADMIN", "MANAGER"] },
  tablesManage: { roles: ["ADMIN"] },
  /** CLEANING -> AVAILABLE: STAFF, MANAGER (ADMIN không kế thừa). */
  tablesClean: { roles: ["MANAGER"] },
} satisfies Record<string, AccessRule>;

/** Có permission hiệu lực (từ /auth/me). */
export function hasPermission(user: CurrentUser | null, permission: Permission): boolean {
  return !!user && user.status === "ACTIVE" && user.permissions.includes(permission);
}

/**
 * Backend (TableAccess.VIEW_SESSIONS): xem danh sách/chi tiết phiên = MANAGER, STAFF, hoặc người có REPORT_VIEW.
 * ADMIN chỉ xem được khi tự cấp REPORT_VIEW (O3, BR-A06).
 */
export function canViewSessions(user: CurrentUser | null): boolean {
  return !!user && user.status === "ACTIVE" && (user.role === "MANAGER" || hasPermission(user, "REPORT_VIEW"));
}

/** Đơn của phiên, Bill, danh sách Cần xử lý (OrderAccess.FLOOR, BillingAccess.FLOOR): chỉ MANAGER/STAFF, ADMIN không kế thừa. */
export function canReadFloorData(user: CurrentUser | null): boolean {
  return !!user && user.status === "ACTIVE" && (user.role === "MANAGER" || user.role === "STAFF");
}
