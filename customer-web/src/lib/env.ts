/**
 * Cấu hình môi trường. Chỉ đọc từ NEXT_PUBLIC_* (không hardcode URL trong code).
 * Next.js inline các biến này lúc build nên phải truy cập trực tiếp `process.env.X`.
 */
function stripTrailingSlash(v: string): string {
  return v.replace(/\/+$/, "");
}

export const env = {
  apiUrl: stripTrailingSlash(process.env.NEXT_PUBLIC_API_URL ?? ""),
  wsUrl: process.env.NEXT_PUBLIC_WS_URL ?? "",
  maxItemQuantity: Number(process.env.NEXT_PUBLIC_MAX_ITEM_QUANTITY ?? 99) || 99,
} as const;
