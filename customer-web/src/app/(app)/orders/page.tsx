"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "@/lib/api/endpoints";
import type { ItemStatus, OrderItem } from "@/lib/api/types";
import { friendlyError } from "@/lib/errors";
import { formatTime, formatVnd } from "@/lib/format";
import { Badge, Banner, Button, EmptyState, ErrorState, Skeleton } from "@/components/ui/primitives";

const STATUS: Record<string, { label: string; tone: "neutral" | "accent" | "ok" | "warn" | "danger" }> = {
  PENDING: { label: "Chờ xác nhận", tone: "warn" },
  CONFIRMED: { label: "Đã xác nhận", tone: "accent" },
  PREPARING: { label: "Đang chế biến", tone: "accent" },
  READY: { label: "Sẵn sàng", tone: "ok" },
  SERVED: { label: "Đã phục vụ", tone: "ok" },
  REJECTED: { label: "Bị từ chối", tone: "danger" },
  CANCELLED: { label: "Đã hủy", tone: "neutral" },
};

const DIRECT_CANCEL: ItemStatus[] = ["PENDING", "CONFIRMED"];
const REQUEST_CANCEL: ItemStatus[] = ["PREPARING", "READY"];
const INACTIVE: ItemStatus[] = ["REJECTED", "CANCELLED"];

export default function OrdersPage() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["orders"], queryFn: api.orders });
  const [confirming, setConfirming] = useState<string | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ["orders"] });

  const cancel = useMutation({
    mutationFn: (item: OrderItem) => (DIRECT_CANCEL.includes(item.status) ? api.cancelItem(item.id) : api.requestCancel(item.id)),
    onSettled: () => {
      setConfirming(null);
      void refresh();
    },
  });

  if (q.isPending) {
    return (
      <div aria-busy="true" aria-label="Đang tải đơn hàng" className="space-y-4 p-4">
        {[0, 1].map((i) => (
          <div key={i} className="space-y-2">
            <Skeleton className="h-4 w-24" />
            <Skeleton className="h-14 w-full" />
            <Skeleton className="h-14 w-full" />
          </div>
        ))}
      </div>
    );
  }
  if (q.isError) return <ErrorState message={friendlyError(q.error)} onRetry={() => void q.refetch()} />;

  const order = q.data;
  if (order.batches.length === 0) {
    return <EmptyState title="Chưa có món nào được gọi" hint="Các đợt gọi món của bàn sẽ hiển thị tại đây." />;
  }

  const locked = order.status === "LOCKED";
  const total = order.batches.flatMap((b) => b.items).filter((i) => !INACTIVE.includes(i.status)).reduce((s, i) => s + i.lineTotal, 0);

  return (
    <div>
      {locked ? <Banner tone="warn">Đơn đang được chốt để thanh toán — không thể hủy món.</Banner> : null}
      {cancel.isError ? <Banner tone="danger">{friendlyError(cancel.error)}</Banner> : null}

      {order.batches.map((b) => (
        <section key={b.id || b.batchNo} aria-label={`Đợt ${b.batchNo}`}>
          <h2 className="flex items-baseline justify-between bg-surface px-4 py-2 text-xs font-medium uppercase tracking-wide text-ink-2">
            <span>Đợt {b.batchNo}</span>
            <span className="normal-case">{formatTime(b.createdAt)}</span>
          </h2>
          <ul className="divide-y divide-line">
            {b.items.map((it) => {
              const st = STATUS[it.status] ?? { label: it.status, tone: "neutral" as const };
              const canDirect = !locked && DIRECT_CANCEL.includes(it.status);
              const canRequest = !locked && REQUEST_CANCEL.includes(it.status) && !it.cancelRequested;
              const busy = cancel.isPending && cancel.variables?.id === it.id;
              return (
                <li key={it.id} className={`px-4 py-3 ${INACTIVE.includes(it.status) ? "opacity-60" : ""}`}>
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="text-[15px] font-medium">{it.quantity} × {it.productName}</p>
                      {it.optionSummary ? <p className="text-sm text-ink-2">{it.optionSummary}</p> : null}
                      {it.note ? <p className="text-sm text-ink-3">Ghi chú: {it.note}</p> : null}
                      {it.status === "REJECTED" && it.rejectReason ? <p className="text-sm text-danger">Lý do: {it.rejectReason}</p> : null}
                    </div>
                    <div className="flex flex-col items-end gap-1">
                      <Badge tone={st.tone}>{st.label}</Badge>
                      <span className="text-sm tabular-nums text-ink-2">{formatVnd(it.lineTotal)}</span>
                    </div>
                  </div>

                  {it.cancelRequested ? <p className="mt-2 text-sm text-warn">Đã gửi yêu cầu hủy — đang chờ quản lý duyệt.</p> : null}

                  {(canDirect || canRequest) && (
                    <div className="mt-2">
                      {confirming === it.id ? (
                        <div className="flex items-center gap-2">
                          <span className="text-sm text-ink-2">{canDirect ? "Hủy món này?" : "Gửi yêu cầu hủy món đang nấu?"}</span>
                          <Button variant="danger" className="h-9 px-3 text-sm" loading={busy} onClick={() => cancel.mutate(it)}>
                            {canDirect ? "Hủy món" : "Gửi yêu cầu"}
                          </Button>
                          <Button variant="ghost" className="h-9 px-3 text-sm" onClick={() => setConfirming(null)}>
                            Không
                          </Button>
                        </div>
                      ) : (
                        <button onClick={() => setConfirming(it.id)} className="text-sm text-danger underline">
                          {canDirect ? "Hủy món" : "Yêu cầu hủy"}
                        </button>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      ))}

      <div className="flex items-baseline justify-between border-t border-line px-4 py-4">
        <span className="text-[15px] text-ink-2">Tổng các món đang gọi</span>
        <span className="text-lg font-semibold tabular-nums">{formatVnd(total)}</span>
      </div>
    </div>
  );
}
