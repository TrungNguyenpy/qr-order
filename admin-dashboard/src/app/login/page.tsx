"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { authApi } from "@/lib/api/auth";
import { canUseDashboard } from "@/lib/access";
import { describeError, type ErrorInfo } from "@/lib/errors";
import { useAuthHydrated } from "@/hooks/useHydrated";
import { useAuthStore } from "@/stores/auth";
import { Banner } from "@/components/ui/Feedback";
import { Button } from "@/components/ui/Button";
import { Field, Input } from "@/components/ui/Field";

/** Chỉ cho phép chuyển hướng nội bộ (chống open redirect). */
function safeNext(next: string | null): string {
  return next && next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/login") ? next : "/reports";
}

function LoginForm() {
  const router = useRouter();
  const qc = useQueryClient();
  const params = useSearchParams();
  const hydrated = useAuthHydrated();
  const token = useAuthStore((s) => s.accessToken);
  const logoutReason = useAuthStore((s) => s.logoutReason);

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<{ username?: string; password?: string }>({});
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);
  const [busy, setBusy] = useState(false);

  // Đã đăng nhập thì vào thẳng.
  useEffect(() => {
    if (hydrated && token) router.replace(safeNext(params.get("next")));
  }, [hydrated, token, router, params]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const next: typeof errors = {};
    if (!username.trim()) next.username = "Vui lòng nhập tên đăng nhập.";
    if (!password) next.password = "Vui lòng nhập mật khẩu.";
    setErrors(next);
    if (Object.keys(next).length > 0) return;

    setBusy(true);
    setApiError(null);
    try {
      const res = await authApi.login(username.trim(), password);
      if (!canUseDashboard(res.user)) {
        // STAFF/KITCHEN dùng Staff App: thu hồi phiên vừa tạo thay vì để token treo.
        void authApi.logout(res.refreshToken).catch(() => undefined);
        setApiError({
          code: "ACCESS_DENIED",
          message: "Tài khoản này không có quyền dùng Admin Dashboard (chỉ ADMIN và MANAGER).",
          fields: {},
          conflict: false,
          reauthFailed: false,
        });
        return;
      }
      qc.clear();
      useAuthStore.getState().setSession(res);
      router.replace(safeNext(params.get("next")));
    } catch (err) {
      setApiError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-dvh items-center justify-center bg-surface px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <div className="mx-auto mb-3 flex h-10 w-10 items-center justify-center rounded-ctl bg-ink text-sm font-semibold text-white">QR</div>
          <h1 className="text-xl font-semibold tracking-tight">QR Order · Quản trị</h1>
          <p className="mt-1 text-ink-2">Đăng nhập bằng tài khoản ADMIN hoặc MANAGER</p>
        </div>
        <form onSubmit={(e) => void submit(e)} noValidate className="space-y-4 rounded-card border border-line bg-bg p-6">
          {logoutReason && !apiError && <Banner tone="warn">{logoutReason}</Banner>}
          {apiError && <Banner tone="danger">{apiError.message}</Banner>}
          <Field label="Tên đăng nhập" error={errors.username}>
            <Input
              data-autofocus
              autoFocus
              autoComplete="username"
              autoCapitalize="none"
              value={username}
              onChange={(e) => {
                setUsername(e.target.value);
                setErrors((p) => ({ ...p, username: undefined }));
              }}
            />
          </Field>
          <Field label="Mật khẩu" error={errors.password}>
            <Input
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                setErrors((p) => ({ ...p, password: undefined }));
              }}
            />
          </Field>
          <Button type="submit" variant="primary" className="w-full" loading={busy}>
            Đăng nhập
          </Button>
        </form>
      </div>
    </main>
  );
}

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginForm />
    </Suspense>
  );
}
