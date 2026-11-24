"use client";

import { useMemo, useState } from "react";
import type { FloorPlanTable, TableDto, TableStatus } from "@/lib/api/types";
import { ACCESS, canAccess } from "@/lib/access";
import { useTableRealtime } from "@/lib/realtime/stomp";
import { describeError, type ErrorInfo } from "@/lib/errors";
import { formatDateTime, formatRelative } from "@/lib/format";
import { useForm } from "@/hooks/useForm";
import { useDebounce } from "@/hooks/useDebounce";
import { useAuthStore } from "@/stores/auth";
import { toast } from "@/stores/toast";
import { Button } from "@/components/ui/Button";
import { DataTable, Pagination, type Column } from "@/components/ui/DataTable";
import { Badge, Banner, EmptyState, QueryBoundary, Skeleton, TableSkeleton } from "@/components/ui/Feedback";
import { Field, Input, Select, Switch } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { Modal } from "@/components/ui/Modal";
import { PageHeader, Toolbar } from "@/components/ui/PageHeader";
import { ReasonDialog } from "@/components/ui/ReasonDialog";
import { RowMenu } from "@/components/ui/RowMenu";
import { Tabs } from "@/components/ui/Tabs";
import { downloadBulkQrPdf, useConfirmCleaning, useCreateTable, useFloorPlan, useSetTableActive, useTables, useUpdateTable } from "./api";
import { SESSION_STATUS_LABEL, TABLE_STATUSES, TABLE_STATUS_LABEL, TABLE_STATUS_TONE } from "./labels";
import { QrModal } from "./QrModal";

const PAGE_SIZE = 15;
const CODE_RE = /^[A-Za-z0-9_-]{1,20}$/;

// ------------------------------------------------------------------------------------------------ Form bàn

interface TableForm {
  code: string;
  displayName: string;
  area: string;
  capacity: string;
  displayOrder: string;
}

export function validateTableForm(v: TableForm, editing: boolean): Partial<Record<keyof TableForm, string>> {
  const e: Partial<Record<keyof TableForm, string>> = {};
  if (!CODE_RE.test(v.code.trim())) e.code = "Mã bàn gồm chữ, số, “-” hoặc “_”, tối đa 20 ký tự.";
  if (!v.displayName.trim()) e.displayName = "Vui lòng nhập tên hiển thị.";
  else if (v.displayName.trim().length > 100) e.displayName = "Tên tối đa 100 ký tự.";
  if (v.area.trim().length > 100) e.area = "Khu vực tối đa 100 ký tự.";
  const cap = v.capacity.trim();
  if (!/^\d+$/.test(cap) || Number(cap) < 1 || Number(cap) > 100) e.capacity = "Sức chứa từ 1 đến 100.";
  const order = v.displayOrder.trim();
  if (order !== "" && !/^\d+$/.test(order)) e.displayOrder = "Nhập số nguyên từ 0 trở lên.";
  if (editing && order === "") e.displayOrder = "Bắt buộc.";
  return e;
}

function TableModal({ table, onClose }: { table: TableDto | null; onClose: () => void }) {
  const create = useCreateTable();
  const update = useUpdateTable();
  const pending = create.isPending || update.isPending;
  const { values, errors, set, setErrors } = useForm<TableForm>({
    code: table?.code ?? "",
    displayName: table?.displayName ?? "",
    area: table?.area ?? "",
    capacity: table ? String(table.capacity) : "4",
    displayOrder: table ? String(table.displayOrder) : "",
  });
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);

  async function submit() {
    const e = validateTableForm(values, !!table);
    setErrors(e);
    if (Object.keys(e).length > 0) return;
    setApiError(null);
    const base = { code: values.code.trim(), displayName: values.displayName.trim(), area: values.area.trim() || undefined, capacity: Number(values.capacity) };
    try {
      if (table) {
        await update.mutateAsync({ id: table.id, ...base, displayOrder: Number(values.displayOrder), version: table.version });
        toast.success("Đã cập nhật bàn.");
      } else {
        const order = values.displayOrder.trim();
        await create.mutateAsync({ ...base, displayOrder: order === "" ? undefined : Number(order) });
        toast.success("Đã thêm bàn. Hãy sinh mã QR cho bàn này.");
      }
      onClose();
    } catch (err) {
      const info = describeError(err);
      setErrors({
        code: info.code === "TABLE_CODE_TAKEN" ? "Mã bàn đã tồn tại (không phân biệt hoa thường)." : info.fields.code,
        displayName: info.fields.displayName,
        area: info.fields.area,
        capacity: info.fields.capacity,
        displayOrder: info.fields.displayOrder,
      });
      setApiError(info.code === "TABLE_CODE_TAKEN" ? null : info);
    }
  }

  return (
    <Modal
      open
      title={table ? "Sửa bàn" : "Thêm bàn"}
      onClose={onClose}
      dismissible={!pending}
      footer={
        <>
          <Button onClick={onClose} disabled={pending}>
            Hủy
          </Button>
          <Button variant="primary" loading={pending} onClick={() => void submit()}>
            {table ? "Lưu" : "Thêm bàn"}
          </Button>
        </>
      }
    >
      <form
        className="space-y-4"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <FormError info={apiError} onReload={onClose} hasFieldErrors={Object.values(errors).some(Boolean)} />
        <div className="grid grid-cols-2 gap-4">
          <Field label="Mã bàn" required error={errors.code} hint="Dùng trong liên kết QR, ví dụ B01.">
            <Input data-autofocus value={values.code} onChange={(e) => set("code", e.target.value)} />
          </Field>
          <Field label="Tên hiển thị" required error={errors.displayName}>
            <Input value={values.displayName} onChange={(e) => set("displayName", e.target.value)} />
          </Field>
        </div>
        <Field label="Khu vực" error={errors.area} hint="Ví dụ: Tầng 1, Sân vườn.">
          <Input value={values.area} onChange={(e) => set("area", e.target.value)} />
        </Field>
        <div className="grid grid-cols-2 gap-4">
          <Field label="Sức chứa" required error={errors.capacity}>
            <Input inputMode="numeric" value={values.capacity} onChange={(e) => set("capacity", e.target.value)} />
          </Field>
          <Field label="Thứ tự hiển thị" required={!!table} error={errors.displayOrder} hint={table ? undefined : "Để trống để đặt cuối."}>
            <Input inputMode="numeric" value={values.displayOrder} onChange={(e) => set("displayOrder", e.target.value)} />
          </Field>
        </div>
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------------------------------------ Sơ đồ bàn

function FloorCard({ item, onOpen }: { item: FloorPlanTable; onOpen: () => void }) {
  const { table, activeSession: s } = item;
  return (
    <button
      type="button"
      onClick={onOpen}
      className="flex flex-col gap-2 rounded-card border border-line bg-surface p-3 text-left transition-colors hover:border-line-strong focus-visible:outline-2 focus-visible:outline-accent"
      aria-label={`${table.displayName}, ${TABLE_STATUS_LABEL[table.status]}`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="truncate font-semibold">{table.displayName}</p>
          <p className="text-xs text-ink-3">
            {table.code} · {table.capacity} chỗ
          </p>
        </div>
        <Badge tone={TABLE_STATUS_TONE[table.status]}>{TABLE_STATUS_LABEL[table.status]}</Badge>
      </div>
      {s ? (
        <p className="text-[13px] text-ink-2">
          Phiên {SESSION_STATUS_LABEL[s.status].toLowerCase()} · {s.deviceCount} thiết bị
          {s.needsAttention && <span className="ml-1 font-medium text-warn">· Cần chú ý</span>}
        </p>
      ) : (
        <p className="text-[13px] text-ink-3">Không có phiên</p>
      )}
      {!table.active && <span className="text-xs text-ink-3">Ngừng sử dụng</span>}
      {table.active && !item.hasActiveQr && <span className="text-xs text-warn">Chưa có mã QR</span>}
    </button>
  );
}

function FloorPlan({ items, onOpen }: { items: FloorPlanTable[]; onOpen: (i: FloorPlanTable) => void }) {
  const [statusFilter, setStatusFilter] = useState<TableStatus | "">("");
  const groups = useMemo(() => {
    const map = new Map<string, FloorPlanTable[]>();
    for (const i of items) {
      if (statusFilter && i.table.status !== statusFilter) continue;
      const key = i.table.area?.trim() || "Chưa phân khu";
      map.set(key, [...(map.get(key) ?? []), i]);
    }
    return [...map.entries()].sort(([a], [b]) => a.localeCompare(b, "vi"));
  }, [items, statusFilter]);

  const counts = useMemo(() => {
    const c: Record<TableStatus, number> = { AVAILABLE: 0, OCCUPIED: 0, PAYMENT_PENDING: 0, CLEANING: 0 };
    for (const i of items) c[i.table.status] += 1;
    return c;
  }, [items]);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Lọc theo trạng thái bàn">
        <Button size="sm" variant={statusFilter === "" ? "primary" : "secondary"} onClick={() => setStatusFilter("")}>
          Tất cả ({items.length})
        </Button>
        {TABLE_STATUSES.map((s) => (
          <Button key={s} size="sm" variant={statusFilter === s ? "primary" : "secondary"} onClick={() => setStatusFilter(s)}>
            {TABLE_STATUS_LABEL[s]} ({counts[s]})
          </Button>
        ))}
      </div>
      {groups.length === 0 && <EmptyState title="Không có bàn ở trạng thái này" />}
      {groups.map(([area, list]) => (
        <section key={area} aria-label={area}>
          <h3 className="mb-2 text-[13px] font-semibold text-ink-2">{area}</h3>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {list.map((i) => (
              <FloorCard key={i.table.id} item={i} onOpen={() => onOpen(i)} />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ Chi tiết bàn

function DetailModal({
  item,
  canManage,
  canClean,
  onClose,
  onEdit,
  onQr,
}: {
  item: FloorPlanTable;
  canManage: boolean;
  canClean: boolean;
  onClose: () => void;
  onEdit: () => void;
  onQr: () => void;
}) {
  const { table, activeSession: s } = item;
  const clean = useConfirmCleaning();
  const [cleanError, setCleanError] = useState<ErrorInfo | null>(null);

  async function confirmCleaning() {
    setCleanError(null);
    try {
      await clean.mutateAsync({ id: table.id, version: table.version });
      toast.success(`Đã xác nhận dọn xong ${table.displayName}.`);
      onClose();
    } catch (e) {
      setCleanError(describeError(e));
    }
  }

  return (
    <Modal open title={table.displayName} description={`Mã ${table.code}${table.area ? ` · ${table.area}` : ""} · ${table.capacity} chỗ`} onClose={onClose}>
      <div className="space-y-4">
        <FormError info={cleanError} onReload={onClose} />
        <div className="flex items-center gap-2">
          <Badge tone={TABLE_STATUS_TONE[table.status]}>{TABLE_STATUS_LABEL[table.status]}</Badge>
          <span className="text-[13px] text-ink-3">từ {formatRelative(table.statusChangedAt)}</span>
          {!table.active && <Badge>Ngừng sử dụng</Badge>}
        </div>
        {s && (
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-card border border-line p-3 text-[13px]">
            <dt className="text-ink-3">Phiên</dt>
            <dd>{SESSION_STATUS_LABEL[s.status]}</dd>
            <dt className="text-ink-3">Mở lúc</dt>
            <dd>{formatDateTime(s.openedAt)}</dd>
            <dt className="text-ink-3">Hoạt động gần nhất</dt>
            <dd>{formatRelative(s.lastActivityAt)}</dd>
            <dt className="text-ink-3">Thiết bị</dt>
            <dd>{s.deviceCount}</dd>
          </dl>
        )}
        {!item.hasActiveQr && table.active && <Banner tone="warn">Bàn chưa có mã QR đang dùng nên khách chưa thể quét để gọi món.</Banner>}
        {table.status === "CLEANING" && canClean && (
          <Banner tone="neutral" title="Bàn đang chờ dọn" action={<Button variant="primary" loading={clean.isPending} onClick={() => void confirmCleaning()}>Xác nhận đã dọn</Button>}>
            Sau khi xác nhận, bàn chuyển về trạng thái Trống.
          </Banner>
        )}
        {table.status === "CLEANING" && !canClean && canManage && <p className="text-[13px] text-ink-3">Chỉ Quản lý hoặc Nhân viên phục vụ xác nhận được bàn đã dọn.</p>}
        {canManage && (
          <div className="flex flex-wrap justify-end gap-2 border-t border-line pt-4">
            <Button onClick={onEdit}>Sửa bàn</Button>
            <Button onClick={onQr}>Mã QR</Button>
          </div>
        )}
      </div>
    </Modal>
  );
}

// ------------------------------------------------------------------------------------------------ Danh sách bàn (ADMIN/MANAGER xem)

function TableList({ canManage, onEdit, onQr }: { canManage: boolean; onEdit: (t: TableDto) => void; onQr: (t: TableDto) => void }) {
  const [q, setQ] = useState("");
  const [area, setArea] = useState("");
  const [status, setStatus] = useState<TableStatus | "">("");
  const [active, setActive] = useState<"" | "true" | "false">("");
  const [page, setPage] = useState(0);
  const dq = useDebounce(q.trim());
  const darea = useDebounce(area.trim());
  const query = useTables({ q: dq, area: darea, status, active, page, size: PAGE_SIZE });
  const setActiveMut = useSetTableActive();
  const [busyId, setBusyId] = useState<number | null>(null);
  const [deactivate, setDeactivate] = useState<TableDto | null>(null);
  const filtered = !!(dq || darea || status || active);

  async function activate(t: TableDto) {
    setBusyId(t.id);
    try {
      await setActiveMut.mutateAsync({ id: t.id, active: true, version: t.version });
      toast.success(`Đã dùng lại ${t.displayName}.`);
    } catch (e) {
      toast.error(describeError(e).message);
    } finally {
      setBusyId(null);
    }
  }

  const columns: Column<TableDto>[] = [
    { key: "code", header: "Mã", cell: (t) => <span className="font-mono text-[13px]">{t.code}</span> },
    { key: "name", header: "Tên bàn", cell: (t) => <span className="font-medium">{t.displayName}</span> },
    { key: "area", header: "Khu vực", cell: (t) => <span className="text-ink-2">{t.area ?? "—"}</span> },
    { key: "cap", header: "Sức chứa", align: "right", cell: (t) => t.capacity },
    { key: "status", header: "Trạng thái", cell: (t) => <Badge tone={TABLE_STATUS_TONE[t.status]}>{TABLE_STATUS_LABEL[t.status]}</Badge> },
    {
      key: "active",
      header: "Sử dụng",
      cell: (t) =>
        canManage ? (
          <Switch
            checked={t.active}
            loading={busyId === t.id}
            label={`Sử dụng ${t.displayName}`}
            onChange={(v) => (v ? void activate(t) : setDeactivate(t))}
          />
        ) : (
          <span className="text-ink-2">{t.active ? "Đang dùng" : "Ngừng"}</span>
        ),
    },
    {
      key: "act",
      header: "",
      align: "right",
      className: "w-12",
      cell: (t) => (
        <RowMenu
          label={`Thao tác với ${t.displayName}`}
          items={[
            { label: "Sửa bàn", onSelect: () => onEdit(t), hidden: !canManage },
            { label: "Mã QR", onSelect: () => onQr(t), hidden: !canManage },
          ]}
        />
      ),
    },
  ];

  return (
    <>
      <Toolbar>
        <div className="w-56">
          <Input
            placeholder="Tìm theo mã hoặc tên"
            aria-label="Tìm bàn"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setPage(0);
            }}
          />
        </div>
        <div className="w-40">
          <Input
            placeholder="Khu vực"
            aria-label="Lọc theo khu vực"
            value={area}
            onChange={(e) => {
              setArea(e.target.value);
              setPage(0);
            }}
          />
        </div>
        <div className="w-44">
          <Select
            aria-label="Lọc theo trạng thái"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as TableStatus | "");
              setPage(0);
            }}
          >
            <option value="">Mọi trạng thái</option>
            {TABLE_STATUSES.map((s) => (
              <option key={s} value={s}>
                {TABLE_STATUS_LABEL[s]}
              </option>
            ))}
          </Select>
        </div>
        <div className="w-40">
          <Select
            aria-label="Lọc theo sử dụng"
            value={active}
            onChange={(e) => {
              setActive(e.target.value as "" | "true" | "false");
              setPage(0);
            }}
          >
            <option value="">Tất cả</option>
            <option value="true">Đang dùng</option>
            <option value="false">Ngừng sử dụng</option>
          </Select>
        </div>
      </Toolbar>
      <QueryBoundary
        query={query}
        skeleton={<TableSkeleton cols={7} />}
        isEmpty={(d) => d.items.length === 0}
        empty={<EmptyState title={filtered ? "Không có bàn phù hợp" : "Chưa có bàn nào"} description={filtered ? "Thử đổi từ khóa hoặc bộ lọc." : canManage ? "Thêm bàn đầu tiên rồi sinh mã QR." : undefined} />}
      >
        {(data) => (
          <>
            <DataTable caption="Danh sách bàn" columns={columns} rows={data.items} rowKey={(t) => t.id} rowClassName={(t) => (t.active ? undefined : "opacity-60")} />
            <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onPage={setPage} />
          </>
        )}
      </QueryBoundary>
      {deactivate && (
        <ReasonDialog
          open
          title={`Ngừng sử dụng ${deactivate.displayName}?`}
          description="Chỉ ngừng được khi bàn đang trống. Bàn ngừng sử dụng không nhận order mới; dữ liệu cũ được giữ nguyên."
          confirmLabel="Ngừng sử dụng"
          tone="danger"
          reason="none"
          onSubmit={async () => {
            await setActiveMut.mutateAsync({ id: deactivate.id, active: false, version: deactivate.version });
            toast.success(`Đã ngừng sử dụng ${deactivate.displayName}.`);
          }}
          onReload={() => setDeactivate(null)}
          onClose={() => setDeactivate(null)}
        />
      )}
    </>
  );
}

// ------------------------------------------------------------------------------------------------ Trang

type Tab = "floor" | "list";
type Dialog = { kind: "detail"; id: number } | { kind: "form"; table: TableDto | null } | { kind: "qr"; table: TableDto } | null;

const REALTIME_LABEL = { connecting: "Đang kết nối…", connected: "Realtime", offline: "Mất kết nối, đang làm mới định kỳ", polling: "Làm mới mỗi 30 giây" } as const;

export function TablesPage() {
  const user = useAuthStore((s) => s.user);
  const canManage = canAccess(user, ACCESS.tablesManage);
  const canClean = canAccess(user, ACCESS.tablesClean);
  const [tab, setTab] = useState<Tab>("floor");
  const [dialog, setDialog] = useState<Dialog>(null);
  const [bulkBusy, setBulkBusy] = useState(false);
  const realtime = useTableRealtime();
  const floor = useFloorPlan(realtime !== "connected");

  async function bulkPdf() {
    setBulkBusy(true);
    try {
      await downloadBulkQrPdf([]);
    } catch (e) {
      toast.error(describeError(e).message);
    } finally {
      setBulkBusy(false);
    }
  }

  const detailItem = dialog?.kind === "detail" ? floor.data?.find((i) => i.table.id === dialog.id) : undefined;

  return (
    <>
      <PageHeader
        title="Bàn & QR"
        description="Sơ đồ bàn theo thời gian thực, quản lý bàn và mã QR."
        actions={
          canManage ? (
            <>
              <Button loading={bulkBusy} onClick={() => void bulkPdf()}>
                Tải PDF mọi mã QR
              </Button>
              <Button variant="primary" onClick={() => setDialog({ kind: "form", table: null })}>
                Thêm bàn
              </Button>
            </>
          ) : undefined
        }
      />
      <div className="mb-1 flex items-center justify-between">
        <Tabs
          tabs={[
            { id: "floor" as const, label: "Sơ đồ bàn" },
            { id: "list" as const, label: "Danh sách" },
          ]}
          value={tab}
          onChange={setTab}
        />
        <span className="mb-4 flex items-center gap-1.5 text-xs text-ink-3" role="status">
          <span className={realtime === "connected" ? "h-2 w-2 rounded-full bg-ok" : realtime === "offline" ? "h-2 w-2 rounded-full bg-warn" : "h-2 w-2 rounded-full bg-line-strong"} aria-hidden />
          {REALTIME_LABEL[realtime]}
        </span>
      </div>

      {tab === "floor" && (
        <QueryBoundary
          query={floor}
          skeleton={
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4" role="status" aria-label="Đang tải">
              {Array.from({ length: 8 }, (_, i) => (
                <Skeleton key={i} className="h-24 w-full" />
              ))}
            </div>
          }
          isEmpty={(d) => d.length === 0}
          empty={<EmptyState title="Chưa có bàn nào đang hoạt động" description={canManage ? "Thêm bàn đầu tiên rồi sinh mã QR." : undefined} />}
        >
          {(items) => <FloorPlan items={items} onOpen={(i) => setDialog({ kind: "detail", id: i.table.id })} />}
        </QueryBoundary>
      )}
      {tab === "list" && <TableList canManage={canManage} onEdit={(t) => setDialog({ kind: "form", table: t })} onQr={(t) => setDialog({ kind: "qr", table: t })} />}

      {detailItem && dialog?.kind === "detail" && (
        <DetailModal
          item={detailItem}
          canManage={canManage}
          canClean={canClean}
          onClose={() => setDialog(null)}
          onEdit={() => setDialog({ kind: "form", table: detailItem.table })}
          onQr={() => setDialog({ kind: "qr", table: detailItem.table })}
        />
      )}
      {dialog?.kind === "form" && <TableModal table={dialog.table} onClose={() => setDialog(null)} />}
      {dialog?.kind === "qr" && <QrModal table={dialog.table} onClose={() => setDialog(null)} />}
    </>
  );
}
