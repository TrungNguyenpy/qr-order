"use client";

import { useToastStore } from "@/stores/toast";
import { cn } from "@/lib/cn";

const TONE = {
  success: "border-ok/40 bg-ok-soft text-ok",
  error: "border-danger/40 bg-danger-soft text-danger",
  info: "border-line-strong bg-bg text-ink",
};

export function Toaster() {
  const items = useToastStore((s) => s.items);
  const dismiss = useToastStore((s) => s.dismiss);
  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-[min(92vw,360px)] flex-col gap-2" aria-live="polite">
      {items.map((t) => (
        <div
          key={t.id}
          role={t.tone === "error" ? "alert" : "status"}
          className={cn("pointer-events-auto flex items-start gap-3 rounded-card border px-4 py-3 text-[13px] shadow-sm", TONE[t.tone])}
        >
          <p className="min-w-0 flex-1">{t.message}</p>
          <button type="button" onClick={() => dismiss(t.id)} aria-label="Đóng thông báo" className="text-current opacity-60 hover:opacity-100">
            ×
          </button>
        </div>
      ))}
    </div>
  );
}
