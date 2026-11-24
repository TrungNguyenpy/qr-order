/** Kiểm tra số tiền hoàn: số nguyên dương, không vượt `remaining_refundable`. Trả thông báo lỗi hoặc null. */
export function validateRefundAmount(raw: string, remainingRefundable: number): string | null {
  const text = raw.trim();
  if (!/^\d+$/.test(text)) return "Nhập số tiền là số nguyên dương (VND).";
  const n = Number(text);
  if (!Number.isSafeInteger(n) || n <= 0) return "Số tiền phải lớn hơn 0.";
  if (n > remainingRefundable) return "Số tiền vượt quá số còn phải hoàn.";
  return null;
}
