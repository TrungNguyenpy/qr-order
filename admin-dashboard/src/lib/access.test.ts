import { describe, expect, it } from "vitest";
import { ACCESS, canAccess, canUseDashboard } from "./access";
import type { CurrentUser } from "./api/types";

const user = (over: Partial<CurrentUser>): CurrentUser => ({
  id: 1,
  username: "u",
  fullName: "U",
  role: "ADMIN",
  status: "ACTIVE",
  permissions: [],
  lastLoginAt: null,
  passwordChangedAt: null,
  ...over,
});

describe("access rules", () => {
  it("chỉ ADMIN và MANAGER vào được dashboard", () => {
    expect(canUseDashboard(user({ role: "ADMIN" }))).toBe(true);
    expect(canUseDashboard(user({ role: "MANAGER" }))).toBe(true);
    expect(canUseDashboard(user({ role: "STAFF" }))).toBe(false);
    expect(canUseDashboard(user({ role: "KITCHEN" }))).toBe(false);
    expect(canUseDashboard(user({ status: "LOCKED" }))).toBe(false);
    expect(canUseDashboard(null)).toBe(false);
  });

  it("nhân sự và cài đặt chỉ dành cho ADMIN", () => {
    expect(canAccess(user({ role: "ADMIN" }), ACCESS.employees)).toBe(true);
    expect(canAccess(user({ role: "MANAGER" }), ACCESS.employees)).toBe(false);
    expect(canAccess(user({ role: "MANAGER" }), ACCESS.settings)).toBe(false);
  });

  it("thực đơn: ADMIN và MANAGER", () => {
    expect(canAccess(user({ role: "ADMIN" }), ACCESS.menu)).toBe(true);
    expect(canAccess(user({ role: "MANAGER" }), ACCESS.menu)).toBe(true);
  });

  it("ADMIN không kế thừa quyền xác nhận dọn bàn của MANAGER", () => {
    expect(canAccess(user({ role: "ADMIN" }), ACCESS.tablesClean)).toBe(false);
    expect(canAccess(user({ role: "MANAGER" }), ACCESS.tablesClean)).toBe(true);
    expect(canAccess(user({ role: "MANAGER" }), ACCESS.tablesManage)).toBe(false);
  });

  it("rule có permission: phải có permission hiệu lực", () => {
    const rule = { roles: ["ADMIN" as const], permission: "REPORT_VIEW" as const };
    expect(canAccess(user({ role: "ADMIN", permissions: [] }), rule)).toBe(false);
    expect(canAccess(user({ role: "ADMIN", permissions: ["REPORT_VIEW"] }), rule)).toBe(true);
  });
});
