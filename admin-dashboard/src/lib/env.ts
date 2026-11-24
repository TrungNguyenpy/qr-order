/**
 * Cấu hình môi trường. Chỉ đọc từ NEXT_PUBLIC_* (không hardcode URL trong code).
 * Next.js inline các biến này lúc build nên phải truy cập trực tiếp `process.env.X`.
 */
function stripTrailingSlash(v: string): string {
  return v.replace(/\/+$/, "");
}

/**
 * Chuẩn hóa địa chỉ STOMP về WebSocket thuần: `http(s)://host/ws` -> `ws(s)://host/ws/websocket`.
 * Giá trị đã là `ws(s)://` được giữ nguyên.
 */
export function toWebSocketUrl(raw: string): string {
  const v = raw.trim();
  if (!v) return "";
  const url = v.replace(/^http(s?):\/\//i, (_m, s: string) => `ws${s}://`).replace(/\/+$/, "");
  return /\/ws$/i.test(url) ? `${url}/websocket` : url;
}

export const env = {
  apiUrl: stripTrailingSlash(process.env.NEXT_PUBLIC_API_URL ?? ""),
  wsUrl: toWebSocketUrl(process.env.NEXT_PUBLIC_WS_URL ?? ""),
  maxImageSizeMb: Number(process.env.NEXT_PUBLIC_MAX_IMAGE_SIZE_MB ?? 5) || 5,
} as const;
