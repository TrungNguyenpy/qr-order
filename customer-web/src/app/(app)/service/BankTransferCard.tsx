"use client";

import { useState } from "react";
import type { BankTransferView } from "@/lib/api/types";
import { formatVnd } from "@/lib/format";

/**
 * Mã VietQR chuyển khoản cho hóa đơn còn tiền phải thu. Ảnh do dịch vụ VietQR dựng từ URL backend trả về
 * (đã gắn số tiền và nội dung = số hóa đơn). Hệ thống không tự đối soát: nhân viên xác nhận đã nhận tiền.
 * Ảnh lỗi (mất mạng, dịch vụ ngoài chậm) thì vẫn còn thông tin để chuyển khoản thủ công.
 */
export function BankTransferCard({ info }: { info: BankTransferView }) {
  const [imageFailed, setImageFailed] = useState(false);

  return (
    <div className="mt-3 rounded-card border border-line p-3" aria-label="Chuyển khoản VietQR">
      <h3 className="text-[15px] font-semibold">Chuyển khoản qua VietQR</h3>
      <p className="mt-0.5 text-xs text-ink-3">Mở app ngân hàng, quét mã để chuyển đúng số tiền. Nhân viên sẽ xác nhận khi nhận được tiền.</p>

      {imageFailed ? (
        <p role="status" className="mt-3 rounded-card bg-surface px-3 py-2 text-sm text-ink-2">
          Không tải được mã QR. Bạn có thể chuyển khoản thủ công theo thông tin bên dưới.
        </p>
      ) : (
        <div className="mt-3 flex justify-center">
          {/* eslint-disable-next-line @next/next/no-img-element -- ảnh QR từ dịch vụ ngoài, URL động theo từng hóa đơn */}
          <img
            src={info.qrImageUrl}
            alt={`Mã VietQR chuyển ${formatVnd(info.amount)}, nội dung ${info.transferNote}`}
            className="h-auto w-full max-w-[280px] rounded-card"
            loading="lazy"
            onError={() => setImageFailed(true)}
          />
        </div>
      )}

      <dl className="mt-3 space-y-1.5 text-sm">
        <Line label="Số tiền" value={formatVnd(info.amount)} strong />
        <Line label="Chủ tài khoản" value={info.accountName} />
        <Line label="Số tài khoản" value={info.accountNo} copy />
        <Line label="Nội dung" value={info.transferNote} copy />
      </dl>
    </div>
  );
}

function Line({ label, value, strong, copy }: { label: string; value: string; strong?: boolean; copy?: boolean }) {
  const [copied, setCopied] = useState(false);
  async function doCopy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      // Trình duyệt chặn clipboard (http, không có quyền): bỏ qua, người dùng vẫn đọc được giá trị.
    }
  }
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="text-ink-2">{label}</dt>
      <dd className={`flex items-center gap-2 text-right tabular-nums ${strong ? "font-semibold" : ""}`}>
        <span className="break-all">{value}</span>
        {copy ? (
          <button type="button" onClick={() => void doCopy()} className="shrink-0 text-xs font-medium text-accent underline-offset-2 hover:underline">
            {copied ? "Đã chép" : "Chép"}
          </button>
        ) : null}
      </dd>
    </div>
  );
}
