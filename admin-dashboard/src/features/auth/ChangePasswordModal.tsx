"use client";

import { useState } from "react";
import { authApi } from "@/lib/api/auth";
import { describeError, type ErrorInfo } from "@/lib/errors";
import { validatePassword } from "@/lib/password";
import { toast } from "@/stores/toast";
import { Button } from "@/components/ui/Button";
import { Field, Input } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { Modal } from "@/components/ui/Modal";

/** Đổi mật khẩu của chính mình. Thành công: backend thu hồi mọi refresh token nên phải đăng nhập lại. */
export function ChangePasswordModal({ onClose, onChanged }: { onClose: () => void; onChanged: () => void }) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [errors, setErrors] = useState<{ current?: string; next?: string; confirm?: string }>({});
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit() {
    const e: typeof errors = {};
    if (!current) e.current = "Vui lòng nhập mật khẩu hiện tại.";
    const policy = validatePassword(next);
    if (policy) e.next = policy;
    else if (next === current) e.next = "Mật khẩu mới phải khác mật khẩu hiện tại.";
    if (confirm !== next) e.confirm = "Mật khẩu nhập lại không khớp.";
    setErrors(e);
    if (Object.keys(e).length > 0) return;

    setBusy(true);
    setApiError(null);
    try {
      await authApi.changePassword(current, next);
      toast.success("Đã đổi mật khẩu. Vui lòng đăng nhập lại.");
      onChanged();
    } catch (err) {
      const info = describeError(err);
      if (info.reauthFailed) setErrors({ current: info.message });
      else {
        setErrors({ current: info.fields.currentPassword, next: info.fields.newPassword });
        setApiError(info);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title="Đổi mật khẩu"
      description="Sau khi đổi, mọi thiết bị đang đăng nhập sẽ bị đăng xuất."
      onClose={onClose}
      dismissible={!busy}
      footer={
        <>
          <Button onClick={onClose} disabled={busy}>
            Hủy
          </Button>
          <Button variant="primary" loading={busy} onClick={() => void submit()}>
            Đổi mật khẩu
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
        <FormError info={apiError} hasFieldErrors={Boolean(errors.current || errors.next)} />
        <Field label="Mật khẩu hiện tại" required error={errors.current}>
          <Input data-autofocus type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        </Field>
        <Field label="Mật khẩu mới" required error={errors.next} hint="Tối thiểu 8 ký tự, gồm chữ hoa, chữ thường và chữ số.">
          <Input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
        </Field>
        <Field label="Nhập lại mật khẩu mới" required error={errors.confirm}>
          <Input type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        </Field>
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}
