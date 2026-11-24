"use client";

import { useState } from "react";
import { describeError, type ErrorInfo } from "@/lib/errors";
import { validatePassword } from "@/lib/password";
import { useForm } from "@/hooks/useForm";
import type { UserDto, UserRole, UserStatus } from "@/lib/api/types";
import { toast } from "@/stores/toast";
import { Button } from "@/components/ui/Button";
import { Field, Input, Select } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { Modal } from "@/components/ui/Modal";
import { ReasonDialog } from "@/components/ui/ReasonDialog";
import { useChangeUserRole, useChangeUserStatus, useCreateUser, useResetPassword, useUpdateUser } from "./api";
import { ROLES, ROLE_LABEL, STATUS_LABEL } from "./labels";

const USERNAME_RE = /^[A-Za-z0-9._-]{3,50}$/;

// ------------------------------------------------------------------------------------------------ Thêm nhân viên

interface CreateForm {
  username: string;
  fullName: string;
  role: UserRole;
  password: string;
  reason: string;
  currentPassword: string;
}
const CREATE_KEYS = ["username", "fullName", "role", "password", "reason", "currentPassword"] as const;

export function CreateUserModal({ onClose }: { onClose: () => void }) {
  const create = useCreateUser();
  const { values, errors, set, setErrors } = useForm<CreateForm>({
    username: "",
    fullName: "",
    role: "STAFF",
    password: "",
    reason: "",
    currentPassword: "",
  });
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);
  // Tạo user role ADMIN = cấp role ADMIN (BR-A08): bắt buộc xác thực lại + lý do.
  const isAdminRole = values.role === "ADMIN";

  async function submit() {
    const e: Partial<Record<keyof CreateForm, string>> = {};
    if (!USERNAME_RE.test(values.username)) e.username = "3-50 ký tự, chỉ gồm chữ không dấu, số, dấu chấm, gạch dưới, gạch ngang.";
    if (!values.fullName.trim()) e.fullName = "Vui lòng nhập họ tên.";
    else if (values.fullName.trim().length > 100) e.fullName = "Họ tên tối đa 100 ký tự.";
    const pw = validatePassword(values.password);
    if (pw) e.password = pw;
    if (isAdminRole) {
      if (!values.reason.trim()) e.reason = "Cấp role ADMIN cần nhập lý do.";
      if (!values.currentPassword) e.currentPassword = "Cần nhập mật khẩu hiện tại để xác thực lại.";
    }
    setErrors(e);
    if (Object.keys(e).length > 0) return;

    setApiError(null);
    try {
      await create.mutateAsync({
        username: values.username.trim(),
        fullName: values.fullName.trim(),
        role: values.role,
        password: values.password,
        ...(isAdminRole ? { reason: values.reason.trim(), currentPassword: values.currentPassword } : {}),
      });
      toast.success(`Đã thêm nhân viên ${values.username.trim()}.`);
      onClose();
    } catch (err) {
      const info = describeError(err);
      if (info.reauthFailed) setErrors({ currentPassword: info.message });
      else {
        const server: Partial<Record<keyof CreateForm, string>> = {};
        for (const k of CREATE_KEYS) if (info.fields[k]) server[k] = info.fields[k];
        setErrors(server);
        setApiError(info);
      }
    }
  }

  return (
    <Modal
      open
      title="Thêm nhân viên"
      onClose={onClose}
      dismissible={!create.isPending}
      footer={
        <>
          <Button onClick={onClose} disabled={create.isPending}>
            Hủy
          </Button>
          <Button variant="primary" loading={create.isPending} onClick={() => void submit()}>
            Thêm nhân viên
          </Button>
        </>
      }
    >
      <form
        className="space-y-4"
        noValidate
        onSubmit={(ev) => {
          ev.preventDefault();
          void submit();
        }}
      >
        <FormError info={apiError} hasFieldErrors={Object.values(errors).some(Boolean)} />
        <Field label="Tên đăng nhập" required error={errors.username} hint="Không đổi được sau khi tạo.">
          <Input data-autofocus autoCapitalize="none" autoComplete="off" value={values.username} onChange={(e) => set("username", e.target.value)} />
        </Field>
        <Field label="Họ tên" required error={errors.fullName}>
          <Input value={values.fullName} onChange={(e) => set("fullName", e.target.value)} />
        </Field>
        <Field label="Vai trò" required error={errors.role}>
          <Select value={values.role} onChange={(e) => set("role", e.target.value as UserRole)}>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {ROLE_LABEL[r]} ({r})
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Mật khẩu ban đầu" required error={errors.password} hint="Tối thiểu 8 ký tự, gồm chữ hoa, chữ thường và chữ số.">
          <Input type="password" autoComplete="new-password" value={values.password} onChange={(e) => set("password", e.target.value)} />
        </Field>
        {isAdminRole && (
          <div className="space-y-4 rounded-card border border-warn/30 bg-warn-soft p-4">
            <p className="text-[13px] text-warn">Tạo tài khoản ADMIN là cấp role ADMIN: cần xác thực lại và ghi lý do (BR-A08).</p>
            <Field label="Lý do" required error={errors.reason}>
              <Input value={values.reason} onChange={(e) => set("reason", e.target.value)} />
            </Field>
            <Field label="Mật khẩu hiện tại của bạn" required error={errors.currentPassword}>
              <Input type="password" autoComplete="current-password" value={values.currentPassword} onChange={(e) => set("currentPassword", e.target.value)} />
            </Field>
          </div>
        )}
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------------------------------------ Sửa họ tên

export function EditUserModal({ user, onClose }: { user: UserDto; onClose: () => void }) {
  const update = useUpdateUser();
  const [fullName, setFullName] = useState(user.fullName);
  const [error, setError] = useState<string>();
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);

  async function submit() {
    const name = fullName.trim();
    if (!name) return setError("Vui lòng nhập họ tên.");
    if (name.length > 100) return setError("Họ tên tối đa 100 ký tự.");
    setApiError(null);
    try {
      await update.mutateAsync({ id: user.id, fullName: name, version: user.version });
      toast.success("Đã cập nhật họ tên.");
      onClose();
    } catch (e) {
      const info = describeError(e);
      setError(info.fields.fullName);
      setApiError(info);
    }
  }

  return (
    <Modal
      open
      title="Sửa họ tên"
      description={`Tài khoản ${user.username}`}
      onClose={onClose}
      dismissible={!update.isPending}
      size="sm"
      footer={
        <>
          <Button onClick={onClose} disabled={update.isPending}>
            Hủy
          </Button>
          <Button variant="primary" loading={update.isPending} onClick={() => void submit()}>
            Lưu
          </Button>
        </>
      }
    >
      <form
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <FormError info={apiError} onReload={onClose} hasFieldErrors={!!error} />
        <Field label="Họ tên" required error={error}>
          <Input
            data-autofocus
            value={fullName}
            onChange={(e) => {
              setFullName(e.target.value);
              setError(undefined);
            }}
          />
        </Field>
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------------------------------------ Khóa / mở khóa / ngừng dùng

export function ChangeStatusDialog({ user, target, onClose, onSelf }: { user: UserDto; target: UserStatus; onClose: () => void; onSelf: () => void }) {
  const change = useChangeUserStatus();
  const labels: Record<UserStatus, string> = {
    ACTIVE: "Mở khóa / kích hoạt lại",
    LOCKED: "Khóa tài khoản",
    DISABLED: "Ngừng sử dụng tài khoản",
  };
  const note =
    target === "ACTIVE"
      ? "Người dùng sẽ đăng nhập lại được."
      : "Có hiệu lực ngay: người dùng bị đăng xuất khỏi mọi thiết bị. Dữ liệu và lịch sử được giữ nguyên (không xóa cứng).";
  return (
    <ReasonDialog
      open
      title={labels[target]}
      description={`${user.fullName} (${user.username}) · hiện: ${STATUS_LABEL[user.status]}`}
      confirmLabel={labels[target]}
      tone={target === "ACTIVE" ? "primary" : "danger"}
      reason="optional"
      onClose={onClose}
      onSubmit={async ({ reason }) => {
        await change.mutateAsync({ id: user.id, status: target, reason });
        toast.success(`Đã chuyển trạng thái sang "${STATUS_LABEL[target]}".`);
        onSelf();
      }}
    >
      <p className="text-[13px] text-ink-2">{note}</p>
    </ReasonDialog>
  );
}

// ------------------------------------------------------------------------------------------------ Đổi role

export function ChangeRoleDialog({ user, isSelf, onClose, onSelfChanged }: { user: UserDto; isSelf: boolean; onClose: () => void; onSelfChanged: () => void }) {
  const change = useChangeUserRole();
  const [role, setRole] = useState<UserRole>(user.role);
  const [roleError, setRoleError] = useState<string>();
  return (
    <ReasonDialog
      open
      title="Đổi vai trò"
      description={`${user.fullName} (${user.username}) · hiện: ${ROLE_LABEL[user.role]}`}
      confirmLabel="Đổi vai trò"
      reason="required"
      password
      onClose={onClose}
      validate={() => {
        if (role !== user.role) return true;
        setRoleError("Vai trò mới phải khác vai trò hiện tại.");
        return false;
      }}
      onSubmit={async ({ reason, currentPassword }) => {
        await change.mutateAsync({ id: user.id, role, reason, currentPassword });
        toast.success(`Đã đổi vai trò thành ${ROLE_LABEL[role]}.`);
        if (isSelf) onSelfChanged();
      }}
    >
      <Field label="Vai trò mới" required error={roleError}>
        <Select
          value={role}
          onChange={(e) => {
            setRole(e.target.value as UserRole);
            setRoleError(undefined);
          }}
        >
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {ROLE_LABEL[r]} ({r})
            </option>
          ))}
        </Select>
      </Field>
      <p className="text-[13px] text-ink-2">
        Đổi vai trò thu hồi phiên đăng nhập của người dùng. Hệ thống luôn giữ ít nhất một ADMIN đang hoạt động. Các quyền bổ sung đã cấp được giữ nguyên.
      </p>
    </ReasonDialog>
  );
}

// ------------------------------------------------------------------------------------------------ Đặt lại mật khẩu

export function ResetPasswordModal({ user, onClose }: { user: UserDto; onClose: () => void }) {
  const reset = useResetPassword();
  const [pw, setPw] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string>();
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);

  async function submit() {
    const policy = validatePassword(pw);
    if (policy) return setError(policy);
    setApiError(null);
    try {
      await reset.mutateAsync({ id: user.id, newPassword: pw, reason: reason.trim() });
      toast.success(`Đã đặt lại mật khẩu cho ${user.username}. Người dùng phải đăng nhập lại.`);
      onClose();
    } catch (e) {
      const info = describeError(e);
      setError(info.fields.newPassword);
      setApiError(info);
    }
  }

  return (
    <Modal
      open
      title="Đặt lại mật khẩu"
      description={`${user.fullName} (${user.username})`}
      onClose={onClose}
      dismissible={!reset.isPending}
      footer={
        <>
          <Button onClick={onClose} disabled={reset.isPending}>
            Hủy
          </Button>
          <Button variant="primary" loading={reset.isPending} onClick={() => void submit()}>
            Đặt lại mật khẩu
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
        <FormError info={apiError} hasFieldErrors={!!error} />
        <Field label="Mật khẩu mới" required error={error} hint="Tối thiểu 8 ký tự, gồm chữ hoa, chữ thường và chữ số. Mọi phiên đăng nhập của người dùng bị thu hồi.">
          <Input
            data-autofocus
            type="password"
            autoComplete="new-password"
            value={pw}
            onChange={(e) => {
              setPw(e.target.value);
              setError(undefined);
            }}
          />
        </Field>
        <Field label="Lý do (tùy chọn)">
          <Input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />
        </Field>
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}
