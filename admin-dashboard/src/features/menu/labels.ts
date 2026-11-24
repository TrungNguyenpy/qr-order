import type { ProductStatus, SelectionType } from "@/lib/api/types";

export const PRODUCT_STATUS_LABEL: Record<ProductStatus, string> = {
  AVAILABLE: "Đang bán",
  SOLD_OUT: "Hết món",
  HIDDEN: "Đã ẩn",
};
export const PRODUCT_STATUS_TONE = { AVAILABLE: "ok", SOLD_OUT: "warn", HIDDEN: "neutral" } as const;
export const PRODUCT_STATUSES: ProductStatus[] = ["AVAILABLE", "SOLD_OUT", "HIDDEN"];

export const SELECTION_LABEL: Record<SelectionType, string> = {
  SINGLE: "Chọn một",
  MULTIPLE: "Chọn nhiều",
};
