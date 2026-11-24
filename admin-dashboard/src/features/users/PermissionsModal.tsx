"use client";

import { useState } from "react";
import { PERMISSIONS, type Permission, type PermissionGrant, type UserDto } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { toast } from "@/stores/toast";
import { Button } from "@/components/ui/Button";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { Badge, Banner, EmptyState, QueryBoundary, TableSkeleton } from "@/components/ui/Feedback";
import { Field, Select } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { ReasonDialog } from "@/components/ui/ReasonDialog";
import { useGrantPermission, usePermissionGrants, useRevokePermission } from "./api";
import { PERMISSION_LABEL, ROLE_LABEL } from "./labels";

/**
 * Cấp / thu hồi permission (FR-USER-03). Tự cấp / tự thu hồi của chính mình bắt buộc nhập lại mật khẩu (BR-A07).
 * Backend thu hồi refresh token của người bị đổi quyền nên khi đổi quyền của chính mình phải đăng nhập lại (onSelfChanged).
 */
export function PermissionsModal({ user, isSelf, onClose, onSelfChanged }: { user: UserDto; isSelf: boolean; onClose: () => void; onSelfChanged: () => void }) {
  const grants = usePermissionGrants(user.id);
  const grant = useGrantPermission(user.id);
  const revoke = useRevokePermission(user.id);
  const [granting, setGranting] = useState<Permission | null>(null);
  const [revoking, setRevoking] = useState<PermissionGrant | null>(null);
  const [pick, setPick] = useState<Permission | "">("");

  const isManager = user.role === "MANAGER";
  const activeSet = new Set((grants.data ?? []).filter((g) => g.active).map((g) => g.permission));
  const available = PERMISSIONS.filter((p) => !activeSet.has(p));

  const columns: Column<PermissionGrant>[] = [
    {
      key: "perm",
      header: "Quyền",
      cell: (g) => (
        <div>
          <p className="font-medium">{PERMISSION_LABEL[g.permission]}</p>
          <p className="text-xs text-ink-3">{g.permission}</p>
        </div>
      ),
    },
    { key: "state", header: "Trạng thái", cell: (g) => (g.active ? <Badge tone="ok">Đang hiệu lực</Badge> : <Badge>Đã thu hồi</Badge>) },
    {
      key: "granted",
      header: "Cấp lúc / lý do",
      cell: (g) => (
        <div className="max-w-[220px]">
          <p>{formatDateTime(g.grantedAt)}</p>
          <p className="truncate text-xs text-ink-3" title={g.reason}>
            {g.reason}
          </p>
        </div>
      ),
    },
    {
      key: "act",
      header: "",
      align: "right",
      cell: (g) =>
        g.active ? (
          <Button size="sm" variant="danger" onClick={() => setRevoking(g)}>
            Thu hồi
          </Button>
        ) : (
          <span className="text-xs text-ink-3" title={g.revokeReason ?? undefined}>
            {formatDateTime(g.revokedAt)}
          </span>
        ),
    },
  ];

  return (
    <>
      <Modal open title="Quyền nghiệp vụ" description={`${user.fullName} (${user.username}) · ${ROLE_LABEL[user.role]}`} onClose={onClose} size="xl">
        <div className="space-y-5">
          {isManager ? (
            <Banner tone="neutral" title="MANAGER mặc định có đủ 8 quyền">
              Không cần cấp thêm (BR-A06).
            </Banner>
          ) : (
            <>
              {user.role === "ADMIN" && (
                <Banner tone="neutral">ADMIN không mặc định kế thừa quyền MANAGER: cần được cấp từng quyền (ví dụ REPORT_VIEW để xem báo cáo).</Banner>
              )}
              <div className="flex flex-wrap items-end gap-3 rounded-card border border-line bg-surface p-4">
                <Field label="Cấp thêm quyền" className="min-w-56 flex-1">
                  <Select value={pick} onChange={(e) => setPick(e.target.value as Permission | "")} disabled={available.length === 0}>
                    <option value="">{available.length === 0 ? "Đã cấp đủ mọi quyền" : "Chọn quyền…"}</option>
                    {available.map((p) => (
                      <option key={p} value={p}>
                        {PERMISSION_LABEL[p]} ({p})
                      </option>
                    ))}
                  </Select>
                </Field>
                <Button variant="primary" disabled={!pick} onClick={() => pick && setGranting(pick)}>
                  Cấp quyền
                </Button>
              </div>
            </>
          )}

          {isSelf && !isManager && (
            <Banner tone="warn">Đây là tài khoản của bạn: tự cấp/thu hồi quyền cần nhập lại mật khẩu, và bạn sẽ phải đăng nhập lại sau khi thay đổi.</Banner>
          )}

          <QueryBoundary
            query={grants}
            skeleton={<TableSkeleton rows={3} cols={4} />}
            isEmpty={(d) => d.length === 0}
            empty={<EmptyState title="Chưa có quyền bổ sung nào" description="Tài khoản này chỉ có quyền theo vai trò." />}
          >
            {(data) => <DataTable caption="Lịch sử cấp quyền" columns={columns} rows={data} rowKey={(g) => g.id} />}
          </QueryBoundary>
        </div>
      </Modal>

      {granting && (
        <ReasonDialog
          open
          title="Cấp quyền"
          description={`${PERMISSION_LABEL[granting]} (${granting}) cho ${user.username}`}
          confirmLabel="Cấp quyền"
          reason="required"
          password={isSelf}
          onClose={() => setGranting(null)}
          onReload={() => setGranting(null)}
          onSubmit={async ({ reason, currentPassword }) => {
            await grant.mutateAsync({ permission: granting, reason, currentPassword: isSelf ? currentPassword : undefined });
            toast.success("Đã cấp quyền.");
            setPick("");
            if (isSelf) onSelfChanged();
          }}
        />
      )}
      {revoking && (
        <ReasonDialog
          open
          title="Thu hồi quyền"
          description={`${PERMISSION_LABEL[revoking.permission]} (${revoking.permission}) của ${user.username}`}
          confirmLabel="Thu hồi"
          tone="danger"
          reason="required"
          password={isSelf}
          onClose={() => setRevoking(null)}
          onReload={() => setRevoking(null)}
          onSubmit={async ({ reason, currentPassword }) => {
            await revoke.mutateAsync({ permission: revoking.permission, reason, currentPassword: isSelf ? currentPassword : undefined });
            toast.success("Đã thu hồi quyền.");
            if (isSelf) onSelfChanged();
          }}
        />
      )}
    </>
  );
}
