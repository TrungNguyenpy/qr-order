import { describe, expect, it } from "vitest";
import { validateOptionRules } from "./optionRules";

const v = (over: Partial<Parameters<typeof validateOptionRules>[0]>) => ({ selectionType: "MULTIPLE" as const, required: false, minSelect: "", maxSelect: "", ...over });

describe("BR-O03 nhóm option", () => {
  it("để trống min/max: hợp lệ (server tự điền)", () => {
    expect(validateOptionRules(v({}))).toEqual({});
    expect(validateOptionRules(v({ selectionType: "SINGLE", required: true }))).toEqual({});
  });
  it("required ⇔ minSelect ≥ 1", () => {
    expect(validateOptionRules(v({ required: true, minSelect: "0" })).minSelect).toBeDefined();
    expect(validateOptionRules(v({ required: false, minSelect: "1" })).minSelect).toBeDefined();
    expect(validateOptionRules(v({ required: true, minSelect: "1" }))).toEqual({});
    expect(validateOptionRules(v({ required: false, minSelect: "0" }))).toEqual({});
  });
  it("SINGLE ⇒ maxSelect = 1", () => {
    expect(validateOptionRules(v({ selectionType: "SINGLE", maxSelect: "2" })).maxSelect).toBeDefined();
    expect(validateOptionRules(v({ selectionType: "SINGLE", maxSelect: "1" }))).toEqual({});
  });
  it("maxSelect ≥ 1 và ≥ minSelect", () => {
    expect(validateOptionRules(v({ maxSelect: "0" })).maxSelect).toBeDefined();
    expect(validateOptionRules(v({ required: true, minSelect: "3", maxSelect: "2" })).maxSelect).toBeDefined();
    expect(validateOptionRules(v({ required: true, minSelect: "2", maxSelect: "3" }))).toEqual({});
  });
  it("giá trị không phải số nguyên bị từ chối", () => {
    expect(validateOptionRules(v({ minSelect: "a" })).minSelect).toBeDefined();
    expect(validateOptionRules(v({ maxSelect: "1.5" })).maxSelect).toBeDefined();
  });
});
