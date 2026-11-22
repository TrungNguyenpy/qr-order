"use client";

import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "@/lib/api/endpoints";
import { ApiError } from "@/lib/api/client";
import { friendlyError } from "@/lib/errors";
import { getDeviceId } from "@/lib/storage";
import { useSessionStore } from "@/stores/session";
import { Button, Skeleton } from "@/components/ui/primitives";

type View = { kind: "joining" } | { kind: "pin" } | { kind: "closed" } | { kind: "error"; message: string; retryable: boolean };

export default function JoinPage() {
  const { token: qrToken } = useParams<{ token: string }>();
  const router = useRouter();
  const [view, setView] = useState<View>({ kind: "joining" });
  const started = useRef(false);

  const join = useCallback(async () => {
    setView({ kind: "joining" });
    try {
      const r = await api.join(qrToken, getDeviceId());
      useSessionStore.getState().setSession({
        qrToken,
        token: r.token,
        sessionId: r.sessionId,
        tableName: r.tableName,
        pinRequired: r.pinRequired,
        canOrder: r.canOrder,
        sessionStatus: r.sessionStatus,
        phase: "active",
      });
      if (r.pinRequired) setView({ kind: "pin" });
      else router.replace("/menu");
    } catch (e) {
      if (e instanceof ApiError && e.code === "SESSION_CLOSED") {
        useSessionStore.getState().markClosed();
        setView({ kind: "closed" });
        return;
      }
      const retryable = !(e instanceof ApiError) || e.status === 0 || e.status === 429 || e.status >= 500;
      setView({ kind: "error", message: friendlyError(e), retryable });
    }
  }, [qrToken, router]);

  useEffect(() => {
    // React StrictMode gọi effect 2 lần ở dev: chỉ join một lần.
    if (started.current) return;
    started.current = true;
    void join();
  }, [join]);

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col justify-center px-5 py-10">
      {view.kind === "joining" && (
        <div aria-busy="true" aria-label="Đang vào bàn">
          <Skeleton className="mb-3 h-6 w-40" />
          <Skeleton className="mb-2 h-4 w-64" />
          <Skeleton className="h-4 w-52" />
        </div>
      )}
      {view.kind === "pin" && <PinForm onDone={() => router.replace("/menu")} />}
      {view.kind === "closed" && (
        <Notice title="Phiên của bàn đã kết thúc" text="Cảm ơn bạn đã dùng bữa. Nếu bạn muốn gọi thêm, hãy quét lại mã QR trên bàn." />
      )}
      {view.kind === "error" && (
        <Notice title="Không thể vào bàn" text={view.message} action={view.retryable ? <Button variant="secondary" onClick={() => void join()}>Thử lại</Button> : null} />
      )}
    </main>
  );
}

function Notice({ title, text, action }: { title: string; text: string; action?: React.ReactNode }) {
  return (
    <div role="alert">
      <h1 className="text-xl font-semibold">{title}</h1>
      <p className="mt-2 text-[15px] text-ink-2">{text}</p>
      {action ? <div className="mt-5">{action}</div> : null}
    </div>
  );
}

function PinForm({ onDone }: { onDone: () => void }) {
  const [pin, setPin] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const valid = /^\d{4}$/.test(pin);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!valid || loading) return;
    setLoading(true);
    setError("");
    try {
      const r = await api.verifyPin(pin);
      const s = useSessionStore.getState();
      if (r.token) s.setToken(r.token);
      s.setSession({ pinRequired: false });
      onDone();
    } catch (err) {
      setError(friendlyError(err));
      setPin("");
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate>
      <h1 className="text-xl font-semibold">Nhập mã PIN của bàn</h1>
      <p className="mt-2 text-[15px] text-ink-2">Mã gồm 4 chữ số, nhân viên sẽ cung cấp hoặc in trên bàn.</p>
      <input
        value={pin}
        onChange={(e) => setPin(e.target.value.replace(/\D/g, "").slice(0, 4))}
        inputMode="numeric"
        autoComplete="one-time-code"
        autoFocus
        aria-label="Mã PIN 4 số"
        aria-invalid={Boolean(error)}
        placeholder="••••"
        className="mt-5 h-14 w-full rounded-ctl border border-line-strong bg-bg text-center text-2xl tracking-[0.5em] outline-none focus:border-accent"
      />
      {error ? <p role="alert" className="mt-2 text-sm text-danger">{error}</p> : null}
      <Button type="submit" className="mt-4 w-full" disabled={!valid} loading={loading}>
        Xác nhận
      </Button>
    </form>
  );
}
