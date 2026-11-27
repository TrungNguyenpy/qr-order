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
  bankBin: "",
  bankAccountNo: "",
  bankAccountName: "",
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
  describe("tài khoản nhận tiền VietQR", () => {
    const bank = { bankBin: "970436", bankAccountNo: "0011001234567", bankAccountName: "NGUYEN VAN A" };

    it("để trống cả ba: gửi null (xóa cấu hình), không lỗi", () => {
      const r = validateSettings(base, 1);
      expect(r.errors).toEqual({});
      expect(r.input).toMatchObject({ bankBin: null, bankAccountNo: null, bankAccountName: null });
    });
    it("đủ ba trường hợp lệ: gửi nguyên giá trị", () => {
      const r = validateSettings({ ...base, ...bank }, 1);
      expect(r.errors).toEqual({});
      expect(r.input).toMatchObject(bank);
    });
    it("bỏ khoảng trắng trong BIN/số tài khoản và gộp khoảng trắng trong tên", () => {
      const r = validateSettings({ ...base, bankBin: " 970 436 ", bankAccountNo: "0011 0012 34567", bankAccountName: "  NGUYEN   VAN A " }, 1);
      expect(r.input).toMatchObject(bank);
    });
    it("thiếu một hoặc hai trường: báo lỗi đúng ô còn thiếu, không gửi", () => {
      const r = validateSettings({ ...base, bankBin: "970436" }, 1);
      expect(r.input).toBeUndefined();
      expect(r.errors.bankBin).toBeUndefined();
      expect(r.errors.bankAccountNo).toBeDefined();
      expect(r.errors.bankAccountName).toBeDefined();
      expect(validateSettings({ ...base, bankAccountName: "A" }, 1).errors.bankBin).toBeDefined();
    });
    it("BIN phải đúng 6 chữ số; số tài khoản 6 đến 19 chữ số", () => {
      expect(validateSettings({ ...base, ...bank, bankBin: "9704" }, 1).errors.bankBin).toBeDefined();
      expect(validateSettings({ ...base, ...bank, bankBin: "97043a" }, 1).errors.bankBin).toBeDefined();
      expect(validateSettings({ ...base, ...bank, bankAccountNo: "12345" }, 1).errors.bankAccountNo).toBeDefined();
      expect(validateSettings({ ...base, ...bank, bankAccountNo: "1".repeat(20) }, 1).errors.bankAccountNo).toBeDefined();
      expect(validateSettings({ ...base, ...bank, bankAccountNo: "1".repeat(19) }, 1).errors).toEqual({});
      expect(validateSettings({ ...base, ...bank, bankAccountNo: "0011-0012" }, 1).errors.bankAccountNo).toBeDefined();
    });
    it("tên chủ tài khoản tối đa 100 ký tự", () => {
      expect(validateSettings({ ...base, ...bank, bankAccountName: "A".repeat(101) }, 1).errors.bankAccountName).toBeDefined();
      expect(validateSettings({ ...base, ...bank, bankAccountName: "A".repeat(100) }, 1).errors).toEqual({});
    });
  });
});
