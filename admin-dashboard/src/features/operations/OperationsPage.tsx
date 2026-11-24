"use client";

import { useState } from "react";
import { Badge, EmptyState, QueryBoundary } from "@/components/ui/Feedback";
import { Button } from "@/components/ui/Button";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { Modal } from "@/components/ui/Modal";
import { NeedPermission } from "@/components/ui/NeedPermission";
import { PageHeader } from "@/components/ui/PageHeader";
import { ReasonDialog } from "@/components/ui/ReasonDialog";
import { Skeleton, ErrorState } from "@/components/ui/Feedback";
import { Tabs } from "@/components/ui/Tabs";
import { toast } from "@/stores/toast";
import { useAuthStore } from "@/stores/auth";
import { canReadFloorData, hasPermission } from "@/lib/access";
import { formatDateTime, formatVnd } from "@/lib/format";
import type { BillingAttentionItem, CancelRequestDto } from "@/lib/api/types";
import { BillPanel, ReplacementBillButton } from "@/features/billing/BillPanel";
import { useApproveCancel, useBillingAttention, useCancelRequests, useDenyCancel, useLatestBill } from "@/features/billing/api";
import { ITEM_STATUS_LABEL, ITEM_STATUS_TONE } from "@/features/orders/labels";

type Tab = "cancel" | "billing";
const TABS = [
  { id: "cancel", label: "Yêu cầu hủy món" },
  { id: "billing", label: "Hóa đơn cần xử lý" },
] as const;

export function OperationsPage() {
  const user = useAuthStore((s) => s.user);
  const [tab, setTab] = useState<Tab>("cancel");
  return (
    <div>
      <PageHeader title="Vận hành" description="Duyệt yêu cầu hủy món và xử lý hóa đơn chờ hoàn tiền / chờ Bill thay thế." />
      <Tabs tabs={TABS} value={tab} onChange={setTab} />
      {tab === "cancel" ? <CancelRequestsTab canApprove={hasPermission(user, "ITEM_CANCEL_APPROVE")} isAdmin={user?.role === "ADMIN"} poll={user?.role === "ADMIN"} /> : <BillingTab floor={canReadFloorData(user)} isAdmin={user?.role === "ADMIN"} poll={user?.role === "ADMIN"} />}
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ Yêu cầu hủy món

function CancelRequestsTab({ canApprove, isAdmin, poll }: { canApprove: boolean; isAdmin: boolean; poll: boolean }) {
  const query = useCancelRequests(canApprove, poll);
  const [target, setTarget] = useState<{ req: CancelRequestDto; mode: "approve" | "deny" } | null>(null);
  const approve = useApproveCancel();
  const deny = useDenyCancel();

  if (!canApprove)
    return (
      <NeedPermission permission="ITEM_CANCEL_APPROVE" isAdmin={isAdmin}>
        Duyệt yêu cầu hủy món cần quyền ITEM_CANCEL_APPROVE.
      </NeedPermission>
    );

  const columns: Column<CancelRequestDto>[] = [
    { key: "table", header: "Bàn", cell: (r) => <span className="font-medium">{r.tableCode}</span> },
    {
      key: "item",
      header: "Món",
      cell: (r) => (
        <span>
          {r.quantity} × {r.productName}
          <span className="block text-xs text-ink-3">Phiên #{r.sessionId}</span>
        </span>
      ),
    },
    {
      key: "st",
      header: "Tình trạng món",
      cell: (r) => (
        <span className="flex flex-wrap items-center gap-1">
          <Badge tone={ITEM_STATUS_TONE[r.currentItemStatus]}>{ITEM_STATUS_LABEL[r.currentItemStatus]}</Badge>
          {r.currentItemStatus !== r.itemStatusAtRequest && <span className="text-xs text-ink-3">(lúc yêu cầu: {ITEM_STATUS_LABEL[r.itemStatusAtRequest]})</span>}
        </span>
      ),
    },
    { key: "why", header: "Lý do khách nêu", cell: (r) => r.requestReason ?? "—" },
    { key: "at", header: "Yêu cầu lúc", cell: (r) => formatDateTime(r.requestedAt) },
    {
      key: "act",
      header: "",
      align: "right",
      cell: (r) => (
        <span className="flex justify-end gap-2">
          <Button size="sm" variant="primary" onClick={() => setTarget({ req: r, mode: "approve" })}>
            Duyệt
          </Button>
          <Button size="sm" variant="danger" onClick={() => setTarget({ req: r, mode: "deny" })}>
            Từ chối
          </Button>
        </span>
      ),
    },
  ];

  return (
    <>
      <QueryBoundary query={query} isEmpty={(d) => d.length === 0} empty={<EmptyState title="Không có yêu cầu hủy món nào đang chờ" description="Danh sách tự cập nhật khi có yêu cầu mới." />}>
        {(rows) => <DataTable caption="Yêu cầu hủy món đang chờ duyệt" rows={rows} rowKey={(r) => r.id} columns={columns} />}
      </QueryBoundary>
      {target?.mode === "approve" && (
        <ReasonDialog
          open
          title={`Duyệt hủy ${target.req.quantity} × ${target.req.productName}`}
          description={`Bàn ${target.req.tableCode}. Món đã được chế biến sẽ được tính là lãng phí.`}
          confirmLabel="Duyệt hủy"
          reason="optional"
          reasonLabel="Ghi chú (tùy chọn)"
          onSubmit={async ({ reason }) => {
            await approve.mutateAsync({ id: target.req.id, note: reason || undefined });
            toast.success("Đã duyệt yêu cầu hủy món.");
          }}
          onClose={() => setTarget(null)}
          onReload={() => setTarget(null)}
        />
      )}
      {target?.mode === "deny" && (
        <ReasonDialog
          open
          title={`Từ chối hủy ${target.req.quantity} × ${target.req.productName}`}
          description={`Bàn ${target.req.tableCode}. Món sẽ tiếp tục được phục vụ và tính tiền.`}
          confirmLabel="Từ chối"
          tone="danger"
          reasonLabel="Lý do từ chối (bắt buộc)"
          onSubmit={async ({ reason }) => {
            await deny.mutateAsync({ id: target.req.id, reason });
            toast.success("Đã từ chối yêu cầu hủy món.");
          }}
          onClose={() => setTarget(null)}
          onReload={() => setTarget(null)}
        />
      )}
    </>
  );
}

// ------------------------------------------------------------------------------------------------ Hóa đơn cần xử lý

function BillingTab({ floor, isAdmin, poll }: { floor: boolean; isAdmin: boolean; poll: boolean }) {
  const query = useBillingAttention(floor, poll);
  const [open, setOpen] = useState<BillingAttentionItem | null>(null);

  if (!floor)
    return (
      <NeedPermission permission="MANAGER/STAFF" isAdmin={isAdmin}>
        Danh sách hóa đơn cần xử lý chỉ dành cho MANAGER/STAFF; backend không cho ADMIN xem dù đã tự cấp quyền (BR-A06).
      </NeedPermission>
    );

  const columns: Column<BillingAttentionItem>[] = [
    { key: "table", header: "Bàn", cell: (i) => <span className="font-medium">{i.tableCode ?? "—"}</span> },
    {
      key: "type",
      header: "Loại",
      cell: (i) =>
        i.type === "BILL_VOID_PENDING" ? <Badge tone="warn">Bill chờ hoàn tiền</Badge> : <Badge tone="danger">Đơn chờ Bill thay thế</Badge>,
    },
    { key: "bill", header: "Hóa đơn", cell: (i) => i.billNumber ?? "—" },
    { key: "refund", header: "Còn phải hoàn", align: "right", cell: (i) => (i.remainingRefundable === null ? "—" : formatVnd(i.remainingRefundable)) },
    { key: "since", header: "Từ lúc", cell: (i) => formatDateTime(i.since) },
    { key: "why", header: "Lý do", cell: (i) => i.reason ?? "—" },
    {
      key: "act",
      header: "",
      align: "right",
      cell: (i) =>
        i.type === "AWAITING_REPLACEMENT_BILL" ? (
          <ReplacementBillButton orderId={i.orderId} billNumber={i.billNumber} />
        ) : (
          <Button size="sm" onClick={() => setOpen(i)}>
            Xử lý
          </Button>
        ),
    },
  ];

  return (
    <>
      <QueryBoundary query={query} isEmpty={(d) => d.length === 0} empty={<EmptyState title="Không có hóa đơn nào cần xử lý" description="Bill chờ hoàn tiền và đơn chờ Bill thay thế sẽ hiện ở đây." />}>
        {(rows) => <DataTable caption="Hóa đơn cần xử lý" rows={rows} rowKey={(i) => `${i.type}-${i.orderId}-${i.billId ?? 0}`} columns={columns} />}
      </QueryBoundary>
      {open && <BillModal item={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function BillModal({ item, onClose }: { item: BillingAttentionItem; onClose: () => void }) {
  const bill = useLatestBill(item.orderId, true);
  return (
    <Modal open size="lg" title={`Hóa đơn bàn ${item.tableCode ?? ""}`} onClose={onClose}>
      {bill.isPending ? (
        <div role="status" aria-label="Đang tải dữ liệu">
          <Skeleton className="h-40" />
        </div>
      ) : bill.isError ? (
        <ErrorState error={bill.error} onRetry={() => void bill.refetch()} compact />
      ) : bill.data === null ? (
        <EmptyState title="Đơn chưa có hóa đơn" />
      ) : (
        <BillPanel bill={bill.data} />
      )}
    </Modal>
  );
}
