"use client";

import { useState, type ReactNode } from "react";
import { describeError, type ErrorInfo } from "@/lib/errors";
import { Button } from "./Button";
import { Field, Input, Textarea } from "./Field";
import { FormError } from "./FormError";
import { Modal } from "./Modal";

export interface ReasonResult {
  reason: string;
  currentPassword: string;
}

interface Props {
  open: boolean;
  title: string;
  description?: string;
  confirmLabel: string;
  tone?: "primary" | "danger";
  /** "required": bắt buộc nhập lý do; "optional": tùy chọn; "none": không có ô lý do (hộp xác nhận thuần). */
  reason?: "required" | "optional" | "none";
  reasonLabel?: string;
  reasonMax?: number;
  /** Yêu cầu nhập lại mật khẩu hiện tại (xác thực lại). 403 REAUTHENTICATION_FAILED hiển thị ngay trên ô này. */
  password?: boolean;
  /** Gửi lên backend. Throw lỗi để hiển thị trong hộp thoại; resolve thì hộp thoại tự đóng. */
  onSubmit: (v: ReasonResult) => Promise<unknown>;
  onClose: () => void;
  /** Gọi khi người dùng bấm "Tải lại dữ liệu" sau lỗi 409. */
  onReload?: () => void;
  /** Kiểm tra thêm của phần `children` trước khi gửi; trả false để dừng (tự hiển thị lỗi ở children). */
  validate?: () => boolean;
  children?: ReactNode;
}

/**
 * Hộp thoại xác nhận thao tác nhạy cảm: lý do (`reason`) và/hoặc mật khẩu xác thực lại (`currentPassword`).
 * Khi backend trả 403 REAUTHENTICATION_FAILED, lỗi hiện ngay trên ô mật khẩu và KHÔNG đăng xuất người dùng.
 */
export function ReasonDialog({
  open,
  title,
  description,
  confirmLabel,
  tone = "primary",
  reason = "required",
  reasonLabel = "Lý do",
  reasonMax = 500,
  password = false,
  onSubmit,
  onClose,
  onReload,
  validate,
  children,
}: Props) {
  const [reasonText, setReasonText] = useState("");
  const [pwd, setPwd] = useState("");
  const [errors, setErrors] = useState<{ reason?: string; password?: string }>({});
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit() {
    const next: typeof errors = {};
    if (reason === "required" && !reasonText.trim()) next.reason = "Vui lòng nhập lý do.";
    if (reasonText.length > reasonMax) next.reason = `Lý do tối đa ${reasonMax} ký tự.`;
    if (password && !pwd) next.password = "Vui lòng nhập mật khẩu hiện tại để xác thực lại.";
    setErrors(next);
    const childrenOk = validate ? validate() : true;
    if (Object.keys(next).length > 0 || !childrenOk) return;

    setBusy(true);
    setApiError(null);
    try {
      await onSubmit({ reason: reasonText.trim(), currentPassword: pwd });
      onClose();
    } catch (e) {
      const info = describeError(e);
      if (info.reauthFailed) setErrors({ password: info.message });
      else {
        setErrors({ reason: info.fields.reason, password: info.fields.currentPassword });
        setApiError(info);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      title={title}
      description={description}
      onClose={onClose}
      dismissible={!busy}
      size="md"
      footer={
        <>
          <Button onClick={onClose} disabled={busy}>
            Hủy
          </Button>
          <Button variant={tone === "danger" ? "danger" : "primary"} onClick={() => void submit()} loading={busy}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
        className="space-y-4"
        noValidate
      >
        <FormError
          info={apiError}
          onReload={onReload}
          hasFieldErrors={Boolean(errors.reason || errors.password)}
        />
        {children}
        {reason !== "none" && (
          <Field label={reasonLabel} required={reason === "required"} error={errors.reason}>
            <Textarea
              data-autofocus
              value={reasonText}
              maxLength={reasonMax + 50}
              onChange={(e) => {
                setReasonText(e.target.value);
                setErrors((p) => ({ ...p, reason: undefined }));
              }}
            />
          </Field>
        )}
        {password && (
          <Field label="Mật khẩu hiện tại của bạn" required error={errors.password} hint="Xác thực lại để thực hiện thao tác này.">
            <Input
              type="password"
              autoComplete="current-password"
              value={pwd}
              onChange={(e) => {
                setPwd(e.target.value);
                setErrors((p) => ({ ...p, password: undefined }));
              }}
            />
          </Field>
        )}
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}
