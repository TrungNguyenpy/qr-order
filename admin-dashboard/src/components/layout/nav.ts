import { ACCESS, type AccessRule } from "@/lib/access";

export interface NavItem {
  href: string;
  label: string;
  rule: AccessRule;
  icon: "table" | "menu" | "users" | "settings" | "report" | "session" | "ops";
}

/** Mục điều hướng. Mục Đợt 2 (báo cáo, phiên, vận hành) tự kiểm tra quyền chi tiết trong trang. */
export const NAV: NavItem[] = [
  { href: "/reports", label: "Tổng quan & Báo cáo", rule: { roles: ["ADMIN", "MANAGER"] }, icon: "report" },
  { href: "/sessions", label: "Phiên & Đơn hàng", rule: { roles: ["ADMIN", "MANAGER"] }, icon: "session" },
  { href: "/operations", label: "Vận hành", rule: { roles: ["ADMIN", "MANAGER"] }, icon: "ops" },
  { href: "/tables", label: "Bàn & QR", rule: ACCESS.tablesView, icon: "table" },
  { href: "/menu/categories", label: "Danh mục", rule: ACCESS.menu, icon: "menu" },
  { href: "/menu/products", label: "Món ăn", rule: ACCESS.menu, icon: "menu" },
  { href: "/menu/options", label: "Option & Topping", rule: ACCESS.menu, icon: "menu" },
  { href: "/employees", label: "Nhân viên", rule: ACCESS.employees, icon: "users" },
  { href: "/settings", label: "Cấu hình", rule: ACCESS.settings, icon: "settings" },
];
