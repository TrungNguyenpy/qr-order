"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import type { UserDto, UserRole, UserStatus } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { useDebounce } from "@/hooks/useDebounce";
import { useAuthStore } from "@/stores/auth";
import { Button } from "@/components/ui/Button";
import { DataTable, Pagination, type Column } from "@/components/ui/DataTable";
import { Badge, EmptyState, QueryBoundary, TableSkeleton } from "@/components/ui/Feedback";
import { Input, Select } from "@/components/ui/Field";
import { PageHeader, Toolbar } from "@/components/ui/PageHeader";
import { RowMenu } from "@/components/ui/RowMenu";
import { useUsers } from "./api";
import { ROLES, ROLE_LABEL, STATUSES, STATUS_LABEL } from "./labels";
import { ChangeRoleDialog, ChangeStatusDialog, CreateUserModal, EditUserModal, ResetPasswordModal } from "./UserModals";
import { PermissionsModal } from "./PermissionsModal";

const PAGE_SIZE = 15;
const STATUS_TONE = { ACTIVE: "ok", LOCKED: "warn", DISABLED: "neutral" } as const;
const ROLE_TONE = { ADMIN: "accent", MANAGER: "ok", STAFF: "neutral", KITCHEN: "neutral" } as const;

type Dialog =
  | { kind: "create" }
  | { kind: "edit"; user: UserDto }
  | { kind: "status"; user: UserDto; target: UserStatus }
  | { kind: "role"; user: UserDto }
  | { kind: "reset"; user: UserDto }
  | { kind: "perm"; user: UserDto };

export function UsersPage() {
  const me = useAuthStore((s) => s.user);
  const qc = useQueryClient();
  const [q, setQ] = useState("");
  const [role, setRole] = useState<UserRole | "">("");
  const [status, setStatus] = useState<UserStatus | "">("");
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const dq = useDebounce(q.trim());

  const query = useUsers({ q: dq, role, status, page, size: PAGE_SIZE });
  const close = () => setDialog(null);
  const reload = () => {
    close();
    void qc.invalidateQueries({ queryKey: ["users"] });
  };

  /** Thay đổi vai trò/quyền/trạng thái của CHÍNH MÌNH làm backend thu hồi refresh token: đăng nhập lại để áp dụng. */
  const endSession = () => useAuthStore.getState().clear("Tài khoản của bạn vừa thay đổi (vai trò, quyền hoặc trạng thái). Vui lòng đăng nhập lại.");

  const columns: Column<UserDto>[] = [
    {
      key: "name",
      header: "Nhân viên",
      cell: (u) => (
        <div>
          <p className="font-medium">
            {u.fullName}
            {u.id === me?.id && <span className="ml-2 text-xs font-normal text-ink-3">(bạn)</span>}
          </p>
          <p className="text-xs text-ink-3">@{u.username}</p>
        </div>
      ),
    },
    { key: "role", header: "Vai trò", cell: (u) => <Badge tone={ROLE_TONE[u.role]}>{ROLE_LABEL[u.role]}</Badge> },
    { key: "status", header: "Trạng thái", cell: (u) => <Badge tone={STATUS_TONE[u.status]}>{STATUS_LABEL[u.status]}</Badge> },
    {
      key: "perms",
      header: "Quyền hiệu lực",
      cell: (u) => (u.role === "MANAGER" ? <span className="text-ink-3">Đủ 8 (mặc định)</span> : u.permissions.length > 0 ? `${u.permissions.length} quyền` : <span className="text-ink-3">—</span>),
    },
    { key: "login", header: "Đăng nhập gần nhất", cell: (u) => <span className="text-ink-2">{formatDateTime(u.lastLoginAt)}</span> },
    {
      key: "act",
      header: "",
      align: "right",
      className: "w-12",
      cell: (u) => {
        const self = u.id === me?.id;
        return (
          <RowMenu
            label={`Thao tác với ${u.fullName}`}
            items={[
              { label: "Sửa họ tên", onSelect: () => setDialog({ kind: "edit", user: u }) },
              { label: "Quyền nghiệp vụ", onSelect: () => setDialog({ kind: "perm", user: u }) },
              { label: "Đổi vai trò", onSelect: () => setDialog({ kind: "role", user: u }) },
              { label: "Đặt lại mật khẩu", hidden: self, onSelect: () => setDialog({ kind: "reset", user: u }) },
              { label: "Mở khóa / kích hoạt", hidden: u.status === "ACTIVE", onSelect: () => setDialog({ kind: "status", user: u, target: "ACTIVE" }) },
              { label: "Khóa tài khoản", hidden: u.status !== "ACTIVE", onSelect: () => setDialog({ kind: "status", user: u, target: "LOCKED" }) },
              { label: "Ngừng sử dụng", danger: true, hidden: u.status === "DISABLED", onSelect: () => setDialog({ kind: "status", user: u, target: "DISABLED" }) },
            ]}
          />
        );
      },
    },
  ];

  const filtered = !!(dq || role || status);

  return (
    <>
      <PageHeader
        title="Nhân viên"
        description="Quản lý tài khoản, vai trò và quyền nghiệp vụ. Không xóa cứng: dùng khóa hoặc ngừng sử dụng."
        actions={
          <Button variant="primary" onClick={() => setDialog({ kind: "create" })}>
            Thêm nhân viên
          </Button>
        }
      />
      <Toolbar>
        <div className="w-64">
          <Input
            placeholder="Tìm theo tên hoặc tên đăng nhập"
            aria-label="Tìm nhân viên"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setPage(0);
            }}
          />
        </div>
        <div className="w-44">
          <Select
            aria-label="Lọc theo vai trò"
            value={role}
            onChange={(e) => {
              setRole(e.target.value as UserRole | "");
              setPage(0);
            }}
          >
            <option value="">Mọi vai trò</option>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {ROLE_LABEL[r]}
              </option>
            ))}
          </Select>
        </div>
        <div className="w-40">
          <Select
            aria-label="Lọc theo trạng thái"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as UserStatus | "");
              setPage(0);
            }}
          >
            <option value="">Mọi trạng thái</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {STATUS_LABEL[s]}
              </option>
            ))}
          </Select>
        </div>
      </Toolbar>

      <QueryBoundary
        query={query}
        skeleton={<TableSkeleton cols={6} />}
        isEmpty={(d) => d.items.length === 0}
        empty={
          <EmptyState
            title={filtered ? "Không có nhân viên phù hợp" : "Chưa có nhân viên nào"}
            description={filtered ? "Thử đổi từ khóa hoặc bộ lọc." : undefined}
            action={!filtered && <Button variant="primary" onClick={() => setDialog({ kind: "create" })}>Thêm nhân viên</Button>}
          />
        }
      >
        {(data) => (
          <>
            <DataTable caption="Danh sách nhân viên" columns={columns} rows={data.items} rowKey={(u) => u.id} />
            <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onPage={setPage} />
          </>
        )}
      </QueryBoundary>

      {dialog?.kind === "create" && <CreateUserModal onClose={close} />}
      {dialog?.kind === "edit" && <EditUserModal user={dialog.user} onClose={close} />}
      {dialog?.kind === "status" && (
        <ChangeStatusDialog
          user={dialog.user}
          target={dialog.target}
          onClose={close}
          onSelf={() => {
            if (dialog.user.id === me?.id && dialog.target !== "ACTIVE") endSession();
          }}
        />
      )}
      {dialog?.kind === "role" && <ChangeRoleDialog user={dialog.user} isSelf={dialog.user.id === me?.id} onClose={close} onSelfChanged={endSession} />}
      {dialog?.kind === "reset" && <ResetPasswordModal user={dialog.user} onClose={close} />}
      {dialog?.kind === "perm" && <PermissionsModal user={dialog.user} isSelf={dialog.user.id === me?.id} onClose={reload} onSelfChanged={endSession} />}
    </>
  );
}
