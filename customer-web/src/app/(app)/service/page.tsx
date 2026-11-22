"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError } from "@/lib/api/client";
import { api } from "@/lib/api/endpoints";
import type { ServiceCallType } from "@/lib/api/types";
import { friendlyError } from "@/lib/errors";
import { formatVnd } from "@/lib/format";
import { Banner, Button, EmptyState, ErrorState, Skeleton } from "@/components/ui/primitives";
import { useSessionStore } from "@/stores/session";

const COOLDOWN_MS = 30_000;

export default function ServicePage() {
  const canOrder = useSessionStore((s) => s.canOrder);
  const [sentTypes, setSentTypes] = useState<ServiceCallType[]>([]);
  const [confirmPay, setConfirmPay] = useState(false);

  const bill = useQuery({
    queryKey: ["bill"],
    queryFn: api.bill,
    // 404 = chưa có bill: là trạng thái "trống" hợp lệ, không retry.
    retry: (n, e) => !(e instanceof ApiError && e.status < 500 && e.status > 0) && n < 2,
  });

  const call = useMutation({
    mutationFn: (type: ServiceCallType) => api.serviceCall(type),
    onSuccess: (_d, type) => {
      setSentTypes((s) => (s.includes(type) ? s : [...s, type]));
      // Hết thời gian chờ thì cho phép gọi lại (server vẫn chống trùng: mỗi loại chỉ một yêu cầu mở).
      setTimeout(() => setSentTypes((s) => s.filter((t) => t !== type)), COOLDOWN_MS);
      setConfirmPay(false);
    },
  });

  const sent = (t: ServiceCallType) => sentTypes.includes(t);
  const pending = (t: ServiceCallType) => call.isPending && call.variables === t;

  return (
    <div className="space-y-6 p-4">
      <section aria-label="Gọi phục vụ" className="space-y-3">
        {call.isError ? <Banner tone="danger">{friendlyError(call.error)}</Banner> : null}
        {sent("CALL_STAFF") ? <Banner tone="ok">Đã gọi nhân viên — vui lòng đợi trong giây lát.</Banner> : null}
        {sent("REQUEST_PAYMENT") ? <Banner tone="ok">Đã gửi yêu cầu thanh toán — nhân viên sẽ đến bàn.</Banner> : null}

        <Button variant="secondary" className="w-full" loading={pending("CALL_STAFF")} disabled={sent("CALL_STAFF")} onClick={() => call.mutate("CALL_STAFF")}>
          Gọi nhân viên
        </Button>

        {confirmPay ? (
          <div className="rounded-card border border-line-strong p-3">
            <p className="text-sm text-ink-2">Gửi yêu cầu tính tiền? Sau khi thanh toán bắt đầu bạn sẽ không gọi thêm món được.</p>
            <div className="mt-3 flex gap-2">
              <Button className="flex-1" loading={pending("REQUEST_PAYMENT")} onClick={() => call.mutate("REQUEST_PAYMENT")}>
                Xác nhận
              </Button>
              <Button variant="ghost" onClick={() => setConfirmPay(false)}>
                Hủy
              </Button>
            </div>
          </div>
        ) : (
          <Button className="w-full" disabled={!canOrder || sent("REQUEST_PAYMENT")} onClick={() => setConfirmPay(true)}>
            Yêu cầu thanh toán
          </Button>
        )}
        {!canOrder ? <p className="text-center text-xs text-ink-3">Bàn đã trong trạng thái chờ thanh toán.</p> : null}
      </section>

      <section aria-label="Hóa đơn tạm tính">
        <h2 className="mb-2 text-[15px] font-semibold">Hóa đơn tạm tính</h2>
        {bill.isPending ? (
          <div aria-busy="true" aria-label="Đang tải hóa đơn" className="space-y-2">
            <Skeleton className="h-5 w-full" />
            <Skeleton className="h-5 w-full" />
            <Skeleton className="h-6 w-1/2" />
          </div>
        ) : bill.isError && bill.error instanceof ApiError && bill.error.status === 404 ? (
          <EmptyState title="Chưa có hóa đơn tạm tính" hint="Hóa đơn sẽ xuất hiện khi nhân viên bắt đầu tính tiền cho bàn." />
        ) : bill.isError ? (
          <ErrorState message={friendlyError(bill.error)} onRetry={() => void bill.refetch()} />
        ) : (
          <div className="rounded-card border border-line">
            <ul className="divide-y divide-line">
              {bill.data.lines.map((l, i) => (
                <li key={i} className="flex justify-between gap-3 px-3 py-2.5 text-[15px]">
                  <span>{l.quantity} × {l.name}</span>
                  <span className="tabular-nums">{formatVnd(l.amount)}</span>
                </li>
              ))}
            </ul>
            <dl className="space-y-1 border-t border-line px-3 py-3 text-sm">
              <Row label="Tạm tính" value={formatVnd(bill.data.subtotal)} />
              {bill.data.discount > 0 ? <Row label="Giảm giá" value={`-${formatVnd(bill.data.discount)}`} /> : null}
              <Row label="Trong đó VAT" value={formatVnd(bill.data.vatAmount)} muted />
              <div className="flex justify-between pt-2 text-base font-semibold">
                <dt>Tổng cộng</dt>
                <dd className="tabular-nums">{formatVnd(bill.data.total)}</dd>
              </div>
            </dl>
          </div>
        )}
      </section>
    </div>
  );
}

function Row({ label, value, muted }: { label: string; value: string; muted?: boolean }) {
  return (
    <div className={`flex justify-between ${muted ? "text-ink-3" : "text-ink-2"}`}>
      <dt>{label}</dt>
      <dd className="tabular-nums">{value}</dd>
    </div>
  );
}
