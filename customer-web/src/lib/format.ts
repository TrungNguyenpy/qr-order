const vnd = new Intl.NumberFormat("vi-VN");

/** Tiền VND là số nguyên (backend làm tròn HALF_UP). */
export function formatVnd(amount: number): string {
  return `${vnd.format(Math.round(amount))}đ`;
}

export function formatTime(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" });
}

export function uuid(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  // Fallback cho trình duyệt cũ / ngữ cảnh không an toàn (http).
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === "x" ? r : (r & 0x3) | 0x8).toString(16);
  });
}
