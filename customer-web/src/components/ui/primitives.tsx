import type { ButtonHTMLAttributes, ReactNode } from "react";

type Variant = "primary" | "secondary" | "ghost" | "danger";

const variants: Record<Variant, string> = {
  primary: "bg-accent text-accent-ink hover:bg-[#2340c8] disabled:bg-line-strong",
  secondary: "bg-bg text-ink border border-line-strong hover:bg-surface disabled:text-ink-3",
  ghost: "bg-transparent text-ink-2 hover:bg-surface disabled:text-ink-3",
  danger: "bg-bg text-danger border border-line-strong hover:bg-danger-soft disabled:text-ink-3",
};

export function Button({
  variant = "primary",
  loading,
  className = "",
  children,
  disabled,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; loading?: boolean }) {
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={`inline-flex h-11 items-center justify-center gap-2 rounded-ctl px-4 text-[15px] font-medium transition-colors disabled:cursor-not-allowed ${variants[variant]} ${className}`}
    >
      {loading ? <span className="size-4 animate-spin rounded-full border-2 border-current border-t-transparent" aria-hidden /> : null}
      {children}
    </button>
  );
}

export function Skeleton({ className = "" }: { className?: string }) {
  return <div className={`qo-skeleton ${className}`} aria-hidden />;
}

export function EmptyState({ title, hint, action }: { title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center px-6 py-14 text-center">
      <div className="mb-3 size-10 rounded-full border border-line-strong" aria-hidden />
      <p className="text-[15px] font-medium">{title}</p>
      {hint ? <p className="mt-1 max-w-xs text-sm text-ink-2">{hint}</p> : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-center px-6 py-14 text-center">
      <div className="mb-3 flex size-10 items-center justify-center rounded-full bg-danger-soft text-lg font-semibold text-danger" aria-hidden>
        !
      </div>
      <p className="text-[15px] font-medium">Không tải được dữ liệu</p>
      <p className="mt-1 max-w-xs text-sm text-ink-2">{message}</p>
      {onRetry ? (
        <Button variant="secondary" className="mt-4" onClick={onRetry}>
          Thử lại
        </Button>
      ) : null}
    </div>
  );
}

const badgeTone = {
  neutral: "bg-surface text-ink-2",
  accent: "bg-accent-soft text-accent",
  ok: "bg-ok-soft text-ok",
  warn: "bg-warn-soft text-warn",
  danger: "bg-danger-soft text-danger",
} as const;

export function Badge({ tone = "neutral", children }: { tone?: keyof typeof badgeTone; children: ReactNode }) {
  return <span className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ${badgeTone[tone]}`}>{children}</span>;
}

export function Banner({ tone = "warn", children }: { tone?: "warn" | "danger" | "ok"; children: ReactNode }) {
  const t = { warn: "bg-warn-soft text-warn", danger: "bg-danger-soft text-danger", ok: "bg-ok-soft text-ok" }[tone];
  return (
    <div role="status" className={`px-4 py-2.5 text-sm ${t}`}>
      {children}
    </div>
  );
}
