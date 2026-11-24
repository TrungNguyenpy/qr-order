"use client";

import { useCallback, useState } from "react";

export type FormErrors<T> = Partial<Record<keyof T, string>>;

/** State form tối giản: giá trị + lỗi theo trường; sửa trường nào thì xóa lỗi trường đó. */
export function useForm<T extends object>(initial: T) {
  const [values, setValues] = useState<T>(initial);
  const [errors, setErrors] = useState<FormErrors<T>>({});

  const set = useCallback(<K extends keyof T>(key: K, value: T[K]) => {
    setValues((p) => ({ ...p, [key]: value }));
    setErrors((p) => (p[key] ? { ...p, [key]: undefined } : p));
  }, []);

  return { values, errors, set, setErrors, setValues };
}

/** Nhận lỗi theo trường từ backend (field -> message), chỉ giữ các trường có trong form. */
export function mergeServerFields<T extends object>(
  fields: Record<string, string>,
  known: ReadonlyArray<keyof T & string>,
): FormErrors<T> {
  const out: FormErrors<T> = {};
  for (const k of known) if (fields[k]) out[k] = fields[k];
  return out;
}
