import { describe, expect, it } from "vitest";
import { validateSettings } from "./SettingsPage";

type Values = Parameters<typeof validateSettings>[0];

const base: Values = {
  vatPercent: "8",
  servicePercent: "5",
  serviceFeeTaxable: "true",
  sessionIdleTimeoutMinutes: "120",
  guestJwtTtlMinutes: "240",
  batchClaimTtlSeconds: "60",
  maxPendingBatchesPerSession: "5",
  joinRateLimitPerMinute: "10",
  orderRateLimitPerMinute: "10",
  maxItemQuantity: "99",
  orderConfirmationMode: "FIRST_BATCH_ONLY",
  requireTablePin: false,
  cleaningAutoRelease: false,
};

describe("validateSettings", () => {
  it("chuyển % sang tỷ lệ và gửi kèm version", () => {
    const r = validateSettings(base, 7);
    expect(r.errors).toEqual({});
    expect(r.input).toMatchObject({ vatRate: 0.08, serviceFeeRate: 0.05, serviceFeeTaxable: true, version: 7 });
  });
  it("BR-P04: phí phục vụ > 0 bắt buộc chốt serviceFeeTaxable", () => {
    const r = validateSettings({ ...base, serviceFeeTaxable: "" }, 1);
    expect(r.errors.serviceFeeTaxable).toBeDefined();
    expect(r.input).toBeUndefined();
  });
  it("phí phục vụ 0 không cần chốt serviceFeeTaxable", () => {
    const r = validateSettings({ ...base, servicePercent: "0", serviceFeeTaxable: "" }, 1);
    expect(r.errors).toEqual({});
    expect(r.input?.serviceFeeTaxable).toBeNull();
  });
  it("VAT trống = chưa cấu hình (null); phần trăm phải < 100 và tối đa 2 số thập phân", () => {
    expect(validateSettings({ ...base, vatPercent: "" }, 1).input?.vatRate).toBeNull();
    expect(validateSettings({ ...base, vatPercent: "100" }, 1).errors.vatPercent).toBeDefined();
    expect(validateSettings({ ...base, vatPercent: "8.123" }, 1).errors.vatPercent).toBeDefined();
    expect(validateSettings({ ...base, vatPercent: "8,5" }, 1).input?.vatRate).toBe(0.085);
  });
  it("các tham số số nguyên phải > 0", () => {
    expect(validateSettings({ ...base, maxItemQuantity: "0" }, 1).errors.maxItemQuantity).toBeDefined();
    expect(validateSettings({ ...base, guestJwtTtlMinutes: "abc" }, 1).errors.guestJwtTtlMinutes).toBeDefined();
  });
});
