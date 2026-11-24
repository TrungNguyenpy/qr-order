import { describe, expect, it } from "vitest";
import { validateTableForm } from "./TablesPage";

const ok = { code: "B01", displayName: "Bàn 1", area: "Tầng 1", capacity: "4", displayOrder: "" };

describe("validateTableForm", () => {
  it("hợp lệ khi tạo mới (thứ tự để trống)", () => {
    expect(validateTableForm(ok, false)).toEqual({});
  });
  it("mã bàn chỉ gồm chữ, số, - và _ (tối đa 20)", () => {
    expect(validateTableForm({ ...ok, code: "B 01" }, false).code).toBeDefined();
    expect(validateTableForm({ ...ok, code: "" }, false).code).toBeDefined();
    expect(validateTableForm({ ...ok, code: "A".repeat(21) }, false).code).toBeDefined();
    expect(validateTableForm({ ...ok, code: "T_1-a" }, false)).toEqual({});
  });
  it("sức chứa 1-100", () => {
    expect(validateTableForm({ ...ok, capacity: "0" }, false).capacity).toBeDefined();
    expect(validateTableForm({ ...ok, capacity: "101" }, false).capacity).toBeDefined();
    expect(validateTableForm({ ...ok, capacity: "100" }, false)).toEqual({});
  });
  it("khi sửa, thứ tự hiển thị là bắt buộc", () => {
    expect(validateTableForm(ok, true).displayOrder).toBeDefined();
    expect(validateTableForm({ ...ok, displayOrder: "3" }, true)).toEqual({});
  });
});
