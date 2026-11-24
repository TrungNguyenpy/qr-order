"use client";

import { useState } from "react";
import { Badge, EmptyState, QueryBoundary } from "@/components/ui/Feedback";
import { DataTable, Pagination, type Column } from "@/components/ui/DataTable";
import { Button } from "@/components/ui/Button";
import { Checkbox, Field, Select } from "@/components/ui/Field";
import { NeedPermission } from "@/components/ui/NeedPermission";
import { PageHeader, Toolbar } from "@/components/ui/PageHeader";
import { useAuthStore } from "@/stores/auth";
import { canViewSessions } from "@/lib/access";
import { formatDateTime, formatRelative } from "@/lib/format";
import type { SessionStatus, StaffSessionDto } from "@/lib/api/types";
import { SESSION_STATUS_LABEL } from "@/features/tables/labels";
import { useSessions, type SessionFilters } from "@/features/billing/api";
import { SessionDetailModal } from "./SessionDetailModal";

const STATUSES: SessionStatus[] = ["OPEN", "BILLING", "CLOSED", "FORCE_CLOSED", "EXPIRED"];
const TONE = { OPEN: "accent", BILLING: "warn", CLOSED: "neutral", FORCE_CLOSED: "danger", EXPIRED: "neutral" } as const;

export function SessionsPage() {
  const user = useAuthStore((s) => s.user);
  const allowed = canViewSessions(user);
  const [filters, setFilters] = useState<SessionFilters>({ status: "", needsAttention: false, page: 0, size: 20 });
  const [selected, setSelected] = useState<number | null>(null);
  // ADMIN không nhận STOMP /topic/tables, nên làm mới định kỳ; MANAGER nhận realtime qua invalidate.
  const query = useSessions(filters, user?.role === "ADMIN");

  const columns: Column<StaffSessionDto>[] = [
    { key: "id", header: "Phiên", cell: (s) => <span className="font-medium">#{s.id}</span> },
    { key: "table", header: "Bàn", cell: (s) => `${s.tableName ?? s.tableCode ?? s.tableId}${s.area ? ` · ${s.area}` : ""}` },
    {
      key: "status",
      header: "Trạng thái",
      cell: (s) => (
        <span className="flex flex-wrap items-center gap-1">
          <Badge tone={TONE[s.status]}>{SESSION_STATUS_LABEL[s.status]}</Badge>
          {s.needsAttention && <Badge tone="warn">Cần xử lý</Badge>}
        </span>
      ),
    },
    { key: "opened", header: "Mở lúc", cell: (s) => formatDateTime(s.openedAt) },
    { key: "last", header: "Hoạt động", cell: (s) => formatRelative(s.lastActivityAt) },
    { key: "dev", header: "Thiết bị", align: "right", cell: (s) => s.deviceCount },
    {
      key: "act",
      header: "",
      align: "right",
      cell: (s) => (
        <Button size="sm" onClick={() => setSelected(s.id)}>
          Chi tiết
        </Button>
      ),
    },
  ];

  return (
    <div>
      <PageHeader title="Phiên & Đơn hàng" description="Giám sát phiên bàn, đơn theo đợt và hóa đơn. Mặc định hiển thị phiên đang mở và đang tính tiền." />
      {!allowed ? (
        <NeedPermission permission="REPORT_VIEW" isAdmin={user?.role === "ADMIN"}>
          Xem danh sách phiên dành cho MANAGER/STAFF hoặc người có quyền REPORT_VIEW.
        </NeedPermission>
      ) : (
        <>
          <Toolbar>
            <Field label="Trạng thái">
              <Select value={filters.status} onChange={(e) => setFilters((f) => ({ ...f, status: e.target.value as SessionStatus | "", page: 0 }))}>
                <option value="">Đang mở + Đang tính tiền</option>
                {STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {SESSION_STATUS_LABEL[s]}
                  </option>
                ))}
              </Select>
            </Field>
            <div className="flex h-9 items-center">
              <Checkbox checked={filters.needsAttention} onChange={(v) => setFilters((f) => ({ ...f, needsAttention: v, page: 0 }))} label="Chỉ phiên cần xử lý" />
            </div>
          </Toolbar>
          <QueryBoundary
            query={query}
            isEmpty={(d) => d.totalElements === 0}
            empty={<EmptyState title="Không có phiên nào" description="Thử đổi bộ lọc trạng thái." />}
          >
            {(data) => (
              <>
                <DataTable caption="Danh sách phiên bàn" rows={data.items} rowKey={(s) => s.id} columns={columns} />
                <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onPage={(p) => setFilters((f) => ({ ...f, page: p }))} />
              </>
            )}
          </QueryBoundary>
        </>
      )}
      {selected !== null && <SessionDetailModal sessionId={selected} onClose={() => setSelected(null)} />}
    </div>
  );
}
