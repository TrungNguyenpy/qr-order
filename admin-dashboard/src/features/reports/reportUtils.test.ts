import { describe, expect, it } from "vitest";
import type { RevenuePeriod } from "@/lib/api/types";
import { compactVnd, defaultRange, formatDuration, formatRatePercent, toChartData, validateRange, vnDate } from "./reportUtils";

describe("khoảng ngày báo cáo", () => {
  it("vnDate dùng giờ Việt Nam (UTC+7)", () => {
    expect(vnDate(new Date("2026-11-23T18:00:00Z"))).toBe("2026-11-24");
    expect(vnDate(new Date("2026-11-23T10:00:00Z"))).toBe("2026-11-23");
  });
  it("mặc định 7 ngày gần nhất gồm hôm nay", () => {
    expect(defaultRange(new Date("2026-11-23T10:00:00Z"))).toEqual({ from: "2026-11-17", to: "2026-11-23" });
  });
  it("kiểm tra khoảng hợp lệ", () => {
    expect(validateRange("2026-11-01", "2026-11-23")).toBeNull();
    expect(validateRange("2026-11-23", "2026-11-23")).toBeNull();
    expect(validateRange("", "2026-11-23")).toBeTruthy();
    expect(validateRange("2026-11-24", "2026-11-23")).toBeTruthy();
    expect(validateRange("2025-01-01", "2026-11-23")).toBeTruthy(); // > 366 ngày
    expect(validateRange("2025-11-23", "2026-11-23")).toBeNull(); // đúng 366 ngày
  });
});

describe("định dạng", () => {
  it("tỷ lệ -> phần trăm", () => {
    expect(formatRatePercent(0.0825)).toBe("8,25%");
    expect(formatRatePercent(0)).toBe("0%");
    expect(formatRatePercent(null)).toBe("—");
  });
  it("thời lượng", () => {
    expect(formatDuration(45)).toBe("45 giây");
    expect(formatDuration(150)).toBe("2 phút 30 giây");
    expect(formatDuration(3660)).toBe("1 giờ 1 phút");
    expect(formatDuration(null)).toBe("—");
  });
  it("số tiền rút gọn cho trục", () => {
    expect(compactVnd(1_250_000)).toBe("1,25tr");
    expect(compactVnd(50_000)).toBe("50k");
    expect(compactVnd(900)).toBe("900");
  });
});

describe("toChartData", () => {
  const p = (over: Partial<RevenuePeriod>): RevenuePeriod => ({
    label: "2026-11-20",
    date: "2026-11-20",
    from: "",
    to: "",
    shiftCode: null,
    shiftName: null,
    grossRevenue: 1_000_000,
    refundedAmount: 100_000,
    netRevenue: 900_000,
    paymentCount: 3,
    billCount: 3,
    refundCount: 1,
    writtenOffCount: 0,
    writtenOffShortfall: 0,
    byMethod: [],
    ...over,
  });
  it("giữ doanh thu ròng/gộp/hoàn", () => {
    expect(toChartData([p({})], "DAY")).toEqual([{ label: "2026-11-20", net: 900_000, gross: 1_000_000, refunded: 100_000 }]);
  });
  it("theo ca: nhãn kèm tên ca", () => {
    expect(toChartData([p({ shiftName: "Ca sáng" })], "SHIFT")[0]?.label).toBe("2026-11-20 · Ca sáng");
  });
});
