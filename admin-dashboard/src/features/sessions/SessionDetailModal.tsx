"use client";

import { useState } from "react";
import { Badge, Banner, ErrorState, Skeleton } from "@/components/ui/Feedback";
import { Button } from "@/components/ui/Button";
import { Modal } from "@/components/ui/Modal";
import { ReasonDialog } from "@/components/ui/ReasonDialog";
import { NeedPermission } from "@/components/ui/NeedPermission";
import { toast } from "@/stores/toast";
import { useAuthStore } from "@/stores/auth";
import { canReadFloorData, hasPermission } from "@/lib/access";
import { formatDateTime, formatRelative, formatVnd } from "@/lib/format";
import type { OrderItemDto, StaffBatchDto, StaffSessionDto } from "@/lib/api/types";
import { SESSION_STATUS_LABEL } from "@/features/tables/labels";
import { BillPanel } from "@/features/billing/BillPanel";
import { useForceClose, useLatestBill, useReopenSession, useSessionDetail, useSessionOrder } from "@/features/billing/api";
import { buildItemTimeline } from "@/features/orders/itemTimeline";
import {
  BATCH_STATUS_LABEL,
  BATCH_STATUS_TONE,
  CANCEL_STATUS_LABEL,
  ITEM_STATUS_LABEL,
  ITEM_STATUS_TONE,
  ORDER_STATUS_LABEL,
  ORDER_STATUS_TONE,
  REASON_CODE_LABEL,
} from "@/features/orders/labels";

function Info({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-ink-3">{label}</dt>
      <dd className="text-ink">{children}</dd>
    </div>
  );
}

function ItemRow({ item }: { item: OrderItemDto }) {
  const [open, setOpen] = useState(false);
  const timeline = buildItemTimeline(item);
  return (
    <li className="border-b border-line py-2 last:border-b-0">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-ink">
            {item.quantity} × {item.productName}
          </p>
          {item.options.length > 0 && <p className="text-xs text-ink-3">{item.options.map((o) => o.optionName).join(", ")}</p>}
          {item.note && <p className="text-xs text-ink-3">Ghi chú: {item.note}</p>}
          {item.reasonCode && <p className="text-xs text-ink-3">Lý do: {REASON_CODE_LABEL[item.reasonCode] ?? item.reasonCode}</p>}
          {item.cancelRequest && <p className="text-xs text-warn">Yêu cầu hủy: {CANCEL_STATUS_LABEL[item.cancelRequest.status]}</p>}
        </div>
        <div className="flex items-center gap-2">
          <span className="tabular-nums text-ink-2">{formatVnd(item.lineTotal)}</span>
          <Badge tone={ITEM_STATUS_TONE[item.status]}>{ITEM_STATUS_LABEL[item.status]}</Badge>
          {item.wasted && <Badge tone="danger">Lãng phí</Badge>}
          <Button size="sm" variant="ghost" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
            {open ? "Ẩn lịch sử" : "Lịch sử"}
          </Button>
        </div>
      </div>
      {open && (
        <ol className="mt-2 space-y-1 border-l-2 border-line pl-3 text-xs text-ink-2">
          {timeline.map((e) => (
            <li key={e.key}>
              <span className="text-ink">{e.label}</span> · {formatDateTime(e.at)}
            </li>
          ))}
          <li className="text-ink-3">Dựng từ các mốc thời gian của món (hệ thống chưa có API lịch sử trạng thái riêng).</li>
        </ol>
      )}
    </li>
  );
}

function BatchBlock({ batch }: { batch: StaffBatchDto }) {
  return (
    <div className="rounded-card border border-line">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line bg-surface px-3 py-2">
        <span className="font-medium text-ink">Đợt #{batch.seq}</span>
        <span className="flex items-center gap-2 text-xs text-ink-2">
          {formatDateTime(batch.submittedAt)}
          <Badge tone={BATCH_STATUS_TONE[batch.status]}>{BATCH_STATUS_LABEL[batch.status]}</Badge>
        </span>
      </div>
      <ul className="px-3 text-[13px]">
        {batch.items.map((i) => (
          <ItemRow key={i.id} item={i} />
        ))}
      </ul>
    </div>
  );
}

/** Chi tiết phiên: thông tin bàn/thiết bị, đơn theo đợt, Bill hiện tại, thao tác force-close / mở lại. */
export function SessionDetailModal({ sessionId, onClose }: { sessionId: number; onClose: () => void }) {
  const user = useAuthStore((s) => s.user);
  const detail = useSessionDetail(sessionId);
  const floor = canReadFloorData(user);
  const order = useSessionOrder(sessionId, floor);
  const orderId = order.data?.orderId ?? null;
  const bill = useLatestBill(orderId, floor);
  const [dialog, setDialog] = useState<"force" | "reopen" | null>(null);
  const forceClose = useForceClose();
  const reopen = useReopenSession();

  const s: StaffSessionDto | undefined = detail.data;
  const canForce = hasPermission(user, "SESSION_FORCE_CLOSE");
  const canReopen = hasPermission(user, "PAYMENT_PROCESS") || hasPermission(user, "SESSION_REOPEN");

  return (
    <Modal open size="xl" title={s ? `Phiên #${s.id} · ${s.tableName ?? s.tableCode ?? `Bàn ${s.tableId}`}` : "Chi tiết phiên"} onClose={onClose}>
      {detail.isPending ? (
        <div className="space-y-3" role="status" aria-label="Đang tải dữ liệu">
          <Skeleton className="h-16" />
          <Skeleton className="h-40" />
        </div>
      ) : detail.isError || !s ? (
        <ErrorState error={detail.error} onRetry={() => void detail.refetch()} compact />
      ) : (
        <div className="space-y-5">
          <section>
            <div className="mb-2 flex flex-wrap items-center gap-2">
              <Badge tone={s.status === "OPEN" ? "accent" : s.status === "BILLING" ? "warn" : s.status === "FORCE_CLOSED" ? "danger" : "neutral"}>{SESSION_STATUS_LABEL[s.status]}</Badge>
              {s.needsAttention && <Badge tone="warn">Cần xử lý: nhàn rỗi còn đơn chưa thanh toán</Badge>}
            </div>
            <dl className="grid grid-cols-2 gap-3 text-[13px] sm:grid-cols-4">
              <Info label="Bàn">{s.tableName ?? s.tableCode ?? "—"}{s.area ? ` · ${s.area}` : ""}</Info>
              <Info label="Thiết bị đã vào">{s.deviceCount}</Info>
              <Info label="Mở lúc">{formatDateTime(s.openedAt)} ({s.openedByType === "STAFF" ? "nhân viên" : "khách"})</Info>
              <Info label="Hoạt động gần nhất">{formatRelative(s.lastActivityAt)}</Info>
              <Info label="Bắt đầu tính tiền">{formatDateTime(s.billingStartedAt)}</Info>
              <Info label="Đóng lúc">{formatDateTime(s.closedAt)}</Info>
              <Info label="Lý do đóng">{s.closeReason ?? "—"}</Info>
              <Info label="PIN bàn">{s.tablePin ?? "—"}</Info>
            </dl>
            <p className="mt-2 text-xs text-ink-3">Backend chỉ trả số thiết bị đã vào phiên, không có danh sách thiết bị.</p>
          </section>

          <section>
            <h3 className="mb-2 text-sm font-semibold text-ink">Đơn hàng</h3>
            {!floor ? (
              <NeedPermission permission="MANAGER/STAFF" isAdmin={user?.role === "ADMIN"}>
                Chi tiết đơn và hóa đơn của phiên chỉ dành cho MANAGER/STAFF (backend không cho ADMIN xem dù đã tự cấp quyền).
              </NeedPermission>
            ) : order.isPending ? (
              <Skeleton className="h-32" />
            ) : order.isError ? (
              <ErrorState error={order.error} onRetry={() => void order.refetch()} compact />
            ) : order.data.orderId === null || order.data.batches.length === 0 ? (
              <p className="rounded-card border border-dashed border-line-strong px-4 py-6 text-center text-ink-2">Phiên này chưa có đơn nào.</p>
            ) : (
              <div className="space-y-3">
                <div className="flex flex-wrap items-center gap-2 text-[13px]">
                  {order.data.status && <Badge tone={ORDER_STATUS_TONE[order.data.status]}>{ORDER_STATUS_LABEL[order.data.status]}</Badge>}
                  <span className="text-ink-2">Tạm tính theo món hợp lệ:</span>
                  <b className="tabular-nums">{formatVnd(order.data.billableTotal)}</b>
                </div>
                {order.data.batches.map((b) => (
                  <BatchBlock key={b.id} batch={b} />
                ))}
              </div>
            )}
          </section>

          {floor && orderId !== null && (
            <section>
              <h3 className="mb-2 text-sm font-semibold text-ink">Hóa đơn hiện tại</h3>
              {bill.isPending ? (
                <Skeleton className="h-32" />
              ) : bill.isError ? (
                <ErrorState error={bill.error} onRetry={() => void bill.refetch()} compact />
              ) : bill.data === null ? (
                <p className="rounded-card border border-dashed border-line-strong px-4 py-6 text-center text-ink-2">Đơn chưa có hóa đơn.</p>
              ) : (
                <BillPanel bill={bill.data} />
              )}
            </section>
          )}

          {(s.status === "OPEN" || s.status === "BILLING") && (
            <section>
              {canForce ? (
                <Banner tone="warn" title="Đóng phiên cưỡng chế" action={<Button variant="danger" size="sm" onClick={() => setDialog("force")}>Force-close</Button>}>
                  Dùng khi khách đã rời bàn nhưng phiên chưa đóng. Các món chưa phục vụ bị hủy; thao tác được ghi audit.
                </Banner>
              ) : (
                <p className="text-xs text-ink-3">Cần quyền SESSION_FORCE_CLOSE để đóng cưỡng chế phiên.</p>
              )}
            </section>
          )}
          {s.status === "CLOSED" && (
            <section>
              {canReopen ? (
                <Banner tone="neutral" title="Mở lại phiên" action={<Button size="sm" onClick={() => setDialog("reopen")}>Mở lại</Button>}>
                  Hóa đơn đã thanh toán sẽ chuyển sang chờ hoàn tiền để hủy, rồi cần tạo Bill thay thế.
                </Banner>
              ) : (
                <p className="text-xs text-ink-3">Cần quyền PAYMENT_PROCESS hoặc SESSION_REOPEN để mở lại phiên.</p>
              )}
            </section>
          )}
        </div>
      )}

      {dialog === "force" && s && (
        <ReasonDialog
          open
          title={`Force-close phiên #${s.id}`}
          description="Phiên và các món chưa phục vụ sẽ bị đóng. Không thể hoàn tác."
          confirmLabel="Đóng cưỡng chế"
          tone="danger"
          reasonLabel="Lý do (bắt buộc)"
          reasonMax={1000}
          onSubmit={async ({ reason }) => {
            await forceClose.mutateAsync({ id: s.id, reason, version: s.version });
            toast.success("Đã đóng cưỡng chế phiên.");
          }}
          onClose={() => setDialog(null)}
          onReload={() => {
            setDialog(null);
            void detail.refetch();
          }}
        />
      )}
      {dialog === "reopen" && s && (
        <ReasonDialog
          open
          title={`Mở lại phiên #${s.id}`}
          confirmLabel="Mở lại phiên"
          reasonLabel="Lý do mở lại (bắt buộc)"
          reasonMax={1000}
          onSubmit={async ({ reason }) => {
            await reopen.mutateAsync({ id: s.id, reason, version: s.version });
            toast.success("Đã mở lại phiên. Hóa đơn cũ chờ hoàn tiền để hủy.");
          }}
          onClose={() => setDialog(null)}
          onReload={() => {
            setDialog(null);
            void detail.refetch();
          }}
        />
      )}
    </Modal>
  );
}
