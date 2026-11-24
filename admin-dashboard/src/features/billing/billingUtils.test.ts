import { describe, expect, it } from "vitest";
import { validateRefundAmount } from "./billingUtils";

describe("validateRefundAmount", () => {
  it("chấp nhận số hợp lệ trong hạn mức", () => {
    expect(validateRefundAmount("50000", 50_000)).toBeNull();
    expect(validateRefundAmount(" 1000 ", 50_000)).toBeNull();
  });
  it("từ chối rỗng, âm, thập phân, chữ, 0", () => {
    for (const v of ["", "-5", "1.5", "abc", "0", "1e3"]) expect(validateRefundAmount(v, 50_000)).toBeTruthy();
  });
  it("từ chối vượt remaining_refundable", () => {
    expect(validateRefundAmount("50001", 50_000)).toBeTruthy();
  });
});
