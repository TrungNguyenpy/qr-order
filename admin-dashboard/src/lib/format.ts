const vnd = new Intl.NumberFormat("vi-VN");

/** Tiền VND nguyên: 1.250.000đ */
export function formatVnd(v: number | null | undefined): string {
  return v === null || v === undefined ? "—" : `${vnd.format(v)}đ`;
}

export function formatNumber(v: number): string {
  return vnd.format(v);
}

/** Hiển thị theo giờ Việt Nam (timezone cố định của hệ thống, BR-C). */
const dateTime = new Intl.DateTimeFormat("vi-VN", {
  timeZone: "Asia/Ho_Chi_Minh",
  dateStyle: "short",
  timeStyle: "short",
});

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : dateTime.format(d);
}

/** "5 phút trước", dùng cho hoạt động gần nhất của phiên. */
export function formatRelative(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return "—";
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return "—";
  const min = Math.max(0, Math.round((now - t) / 60_000));
  if (min < 1) return "vừa xong";
  if (min < 60) return `${min} phút trước`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h} giờ trước`;
  return `${Math.floor(h / 24)} ngày trước`;
}

/** Tỷ lệ thập phân (0.08) -> số phần trăm (8), tránh sai số dấu phẩy động (tối đa 2 chữ số thập phân). */
export function rateToPercent(rate: number): number {
  return Math.round(rate * 10_000) / 100;
}

/** Số phần trăm (8) -> tỷ lệ thập phân (0.08), tối đa 4 chữ số thập phân như backend. */
export function percentToRate(percent: number): number {
  return Math.round(percent * 100) / 10_000;
}
