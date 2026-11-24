import type { PaymentMethod, ReportGroupBy, RevenuePeriod } from "@/lib/api/types";

export const MAX_RANGE_DAYS = 366;

export const GROUP_LABEL: Record<ReportGroupBy, string> = { DAY: "Theo ngày", WEEK: "Theo tuần", MONTH: "Theo tháng", SHIFT: "Theo ca" };
export const METHOD_LABEL: Record<PaymentMethod, string> = { CASH: "Tiền mặt", BANK_TRANSFER: "Chuyển khoản", CARD_POS: "Quẹt thẻ (POS)", OTHER: "Khác" };
export const SEGMENT_LABEL: Record<string, string> = {
  CONFIRMATION: "Chờ xác nhận",
  KITCHEN_QUEUE: "Chờ bếp nhận",
  COOKING: "Chế biến",
  DELIVERY: "Mang món ra",
  TOTAL: "Tổng thời gian",
};
export const REASON_LABEL: Record<string, string> = {
  OUT_OF_STOCK: "Hết món",
  SESSION_FORCE_CLOSED: "Đóng phiên cưỡng bức",
};

/** Ngày yyyy-MM-dd theo giờ Việt Nam (timezone cố định của hệ thống). */
export function vnDate(d: Date): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Ho_Chi_Minh", year: "numeric", month: "2-digit", day: "2-digit" }).format(d);
}

/** 7 ngày gần nhất, tính cả hôm nay (giờ Việt Nam). */
export function defaultRange(now: Date = new Date()): { from: string; to: string } {
  const to = vnDate(now);
  const from = vnDate(new Date(now.getTime() - 6 * 86_400_000));
  return { from, to };
}

/** Kiểm tra khoảng ngày trước khi gọi API; trả thông báo lỗi hoặc null. Backend kiểm tra lại (INVALID_REPORT_RANGE). */
export function validateRange(from: string, to: string): string | null {
  const re = /^\d{4}-\d{2}-\d{2}$/;
  if (!re.test(from) || !re.test(to)) return "Chọn đủ ngày bắt đầu và ngày kết thúc.";
  const a = Date.parse(`${from}T00:00:00Z`);
  const b = Date.parse(`${to}T00:00:00Z`);
  if (Number.isNaN(a) || Number.isNaN(b)) return "Ngày không hợp lệ.";
  if (a > b) return "Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.";
  const days = Math.floor((b - a) / 86_400_000) + 1;
  if (days > MAX_RANGE_DAYS) return `Khoảng tối đa ${MAX_RANGE_DAYS} ngày.`;
  return null;
}

/** 0.0825 -> "8,25%" (tối đa 2 chữ số thập phân). */
export function formatRatePercent(rate: number | null | undefined): string {
  if (rate === null || rate === undefined || Number.isNaN(Number(rate))) return "—";
  return `${new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 2 }).format(Number(rate) * 100)}%`;
}

/** 150 -> "2 phút 30 giây"; null -> "—". */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(Number(seconds))) return "—";
  const total = Math.round(Number(seconds));
  if (total < 60) return `${total} giây`;
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const parts: string[] = [];
  if (h > 0) parts.push(`${h} giờ`);
  if (m > 0) parts.push(`${m} phút`);
  if (s > 0 && h === 0) parts.push(`${s} giây`);
  return parts.join(" ");
}

export interface RevenuePoint {
  label: string;
  net: number;
  gross: number;
  refunded: number;
}

/** Điểm biểu đồ doanh thu: nhãn kỳ ngắn gọn; theo ca thì kèm tên ca. */
export function toChartData(periods: RevenuePeriod[], groupBy: ReportGroupBy): RevenuePoint[] {
  return periods.map((p) => ({
    label: groupBy === "SHIFT" && p.shiftName ? `${p.label} · ${p.shiftName}` : p.label,
    net: Number(p.netRevenue),
    gross: Number(p.grossRevenue),
    refunded: Number(p.refundedAmount),
  }));
}

/** Rút gọn số tiền cho trục biểu đồ: 1.250.000 -> "1,25tr". */
export function compactVnd(v: number): string {
  const abs = Math.abs(v);
  const f = (n: number) => new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 2 }).format(n);
  if (abs >= 1_000_000_000) return `${f(v / 1_000_000_000)}tỷ`;
  if (abs >= 1_000_000) return `${f(v / 1_000_000)}tr`;
  if (abs >= 1_000) return `${f(v / 1_000)}k`;
  return f(v);
}
