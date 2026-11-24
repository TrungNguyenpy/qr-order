"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { cn } from "@/lib/cn";

export interface RowMenuItem {
  label: string;
  onSelect: () => void;
  danger?: boolean;
  disabled?: boolean;
  /** Ẩn hoàn toàn mục (ví dụ thiếu quyền). */
  hidden?: boolean;
}

/** Menu thao tác của một dòng; render qua portal để không bị bảng (overflow) cắt. */
export function RowMenu({ items, label }: { items: RowMenuItem[]; label: string }) {
  const [pos, setPos] = useState<{ top: number; right: number } | null>(null);
  const btn = useRef<HTMLButtonElement>(null);
  const visible = items.filter((i) => !i.hidden);

  useEffect(() => {
    if (!pos) return;
    const close = () => setPos(null);
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    window.addEventListener("scroll", close, true);
    window.addEventListener("resize", close);
    document.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", close);
      document.removeEventListener("keydown", onKey);
    };
  }, [pos]);

  if (visible.length === 0) return null;

  return (
    <>
      <button
        ref={btn}
        type="button"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={!!pos}
        onClick={() => {
          const r = btn.current?.getBoundingClientRect();
          if (r) setPos({ top: r.bottom + 4, right: window.innerWidth - r.right });
        }}
        className="rounded-ctl p-1.5 text-ink-3 hover:bg-surface-2 hover:text-ink"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <circle cx="5" cy="12" r="1.7" />
          <circle cx="12" cy="12" r="1.7" />
          <circle cx="19" cy="12" r="1.7" />
        </svg>
      </button>
      {pos &&
        createPortal(
          <>
            <div className="fixed inset-0 z-40" onClick={() => setPos(null)} aria-hidden="true" />
            <div
              role="menu"
              style={{ top: pos.top, right: pos.right }}
              className="fixed z-50 min-w-48 rounded-card border border-line bg-bg py-1 shadow-sm"
            >
              {visible.map((i) => (
                <button
                  key={i.label}
                  role="menuitem"
                  type="button"
                  disabled={i.disabled}
                  onClick={() => {
                    setPos(null);
                    i.onSelect();
                  }}
                  className={cn(
                    "block w-full px-3 py-2 text-left text-[13px] hover:bg-surface disabled:cursor-not-allowed disabled:opacity-40",
                    i.danger && "text-danger",
                  )}
                >
                  {i.label}
                </button>
              ))}
            </div>
          </>,
          document.body,
        )}
    </>
  );
}
