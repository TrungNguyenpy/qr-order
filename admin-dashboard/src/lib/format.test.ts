import { describe, expect, it } from "vitest";
import { formatRelative, percentToRate, rateToPercent } from "./format";

describe("quy đổi tỷ lệ", () => {
  it("0.08 <-> 8% không sai số dấu phẩy động", () => {
    expect(rateToPercent(0.08)).toBe(8);
    expect(percentToRate(8)).toBe(0.08);
    expect(rateToPercent(0.0725)).toBe(7.25);
    expect(percentToRate(7.25)).toBe(0.0725);
    expect(percentToRate(10)).toBe(0.1);
  });
});

describe("formatRelative", () => {
  const now = Date.parse("2026-11-23T10:00:00Z");
  it("đổi mốc thời gian sang chữ", () => {
    expect(formatRelative("2026-11-23T09:59:50Z", now)).toBe("vừa xong");
    expect(formatRelative("2026-11-23T09:55:00Z", now)).toBe("5 phút trước");
    expect(formatRelative("2026-11-23T07:00:00Z", now)).toBe("3 giờ trước");
    expect(formatRelative(null, now)).toBe("—");
  });
});
