import type { SelectionType } from "@/lib/api/types";

export interface OptionGroupFormValues {
  selectionType: SelectionType;
  required: boolean;
  minSelect: string;
  maxSelect: string;
}

/**
 * Kiểm tra BR-O03 phía client (backend và CHECK của DB kiểm tra lại):
 * `required` ⇔ `minSelect ≥ 1`; `SINGLE` ⇒ `maxSelect = 1`; `maxSelect` null = không giới hạn,
 * khác null thì ≥ 1 và ≥ `minSelect`. Bỏ trống thì server tự điền theo `required`/`selectionType`.
 */
export function validateOptionRules(v: OptionGroupFormValues): { minSelect?: string; maxSelect?: string } {
  const errors: { minSelect?: string; maxSelect?: string } = {};
  const minRaw = v.minSelect.trim();
  const maxRaw = v.maxSelect.trim();
  const min = minRaw === "" ? null : /^\d+$/.test(minRaw) ? Number(minRaw) : NaN;
  const max = maxRaw === "" ? null : /^\d+$/.test(maxRaw) ? Number(maxRaw) : NaN;

  if (Number.isNaN(min)) errors.minSelect = "Nhập số nguyên từ 0 trở lên.";
  if (Number.isNaN(max)) errors.maxSelect = "Nhập số nguyên từ 1 trở lên.";
  if (errors.minSelect || errors.maxSelect) return errors;

  if (min !== null) {
    if (v.required && min < 1) errors.minSelect = "Nhóm bắt buộc phải chọn tối thiểu 1.";
    if (!v.required && min !== 0) errors.minSelect = "Nhóm không bắt buộc phải có tối thiểu 0.";
  }
  if (max !== null) {
    if (max < 1) errors.maxSelect = "Tối đa phải từ 1 trở lên.";
    else if (v.selectionType === "SINGLE" && max !== 1) errors.maxSelect = "Nhóm chọn một chỉ cho phép tối đa 1.";
    else if (min !== null && max < min) errors.maxSelect = "Tối đa phải lớn hơn hoặc bằng tối thiểu.";
    else if (min === null && v.required && max < 1) errors.maxSelect = "Tối đa phải từ 1 trở lên.";
  }
  return errors;
}
