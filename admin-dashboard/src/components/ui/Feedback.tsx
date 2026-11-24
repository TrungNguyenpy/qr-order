import type { ReactNode } from "react";
import type { UseQueryResult } from "@tanstack/react-query";
import { cn } from "@/lib/cn";
import { friendlyError } from "@/lib/errors";
import { Button } from "./Button";

export function Spinner({ className }: { className?: string }) {
  return (
    <svg className={cn("qo-spin h-4 w-4", className)} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="3" opacity="0.25" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("qo-skeleton", className)} aria-hidden="true" />;
}

/** Skeleton dạng bảng: dùng làm trạng thái Loading cho mọi danh sách. */
export function TableSkeleton({ rows = 6, cols = 5 }: { rows?: number; cols?: number }) {
  return (
    <div className="rounded-card border border-line" role="status" aria-label="Đang tải dữ liệu">
      <div className="flex gap-4 border-b border-line bg-surface px-4 py-3">
        {Array.from({ length: cols }, (_, i) => (
          <Skeleton key={i} className="h-3 flex-1" />
        ))}
      </div>
      {Array.from({ length: rows }, (_, r) => (
        <div key={r} className="flex gap-4 border-b border-line px-4 py-4 last:border-b-0">
          {Array.from({ length: cols }, (_, c) => (
            <Skeleton key={c} className="h-4 flex-1" />
          ))}
        </div>
      ))}
    </div>
  );
}

export function EmptyState({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center rounded-card border border-dashed border-line-strong px-6 py-14 text-center">
      <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-full bg-surface-2 text-ink-3" aria-hidden="true">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
          <path d="M4 7l8-4 8 4v10l-8 4-8-4V7z" />
          <path d="M4 7l8 4 8-4M12 11v10" />
        </svg>
      </div>
      <p className="font-medium text-ink">{title}</p>
      {description && <p className="mt-1 max-w-sm text-ink-2">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry, compact }: { error: unknown; onRetry?: () => void; compact?: boolean }) {
  return (
    <div
      role="alert"
      className={cn(
        "flex flex-col items-center rounded-card border border-line bg-danger-soft px-6 text-center",
        compact ? "py-6" : "py-14",
      )}
    >
      <p className="font-medium text-danger">Không tải được dữ liệu</p>
      <p className="mt-1 max-w-md text-ink-2">{friendlyError(error)}</p>
      {onRetry && (
        <Button className="mt-4" onClick={onRetry}>
          Thử lại
        </Button>
      )}
    </div>
  );
}

type Tone = "neutral" | "ok" | "warn" | "danger" | "accent";
const TONE: Record<Tone, string> = {
  neutral: "bg-surface-2 text-ink-2",
  ok: "bg-ok-soft text-ok",
  warn: "bg-warn-soft text-warn",
  danger: "bg-danger-soft text-danger",
  accent: "bg-accent-soft text-accent",
};

export function Badge({ tone = "neutral", children, className }: { tone?: Tone; children: ReactNode; className?: string }) {
  return (
    <span className={cn("inline-flex items-center whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium", TONE[tone], className)}>
      {children}
    </span>
  );
}

export function Banner({
  tone = "neutral",
  title,
  children,
  action,
}: {
  tone?: Tone;
  title?: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  const border = tone === "danger" ? "border-danger/30" : tone === "warn" ? "border-warn/30" : tone === "ok" ? "border-ok/30" : "border-line";
  return (
    <div role={tone === "danger" ? "alert" : "status"} className={cn("flex items-start gap-3 rounded-card border px-4 py-3", TONE[tone], border)}>
      <div className="min-w-0 flex-1">
        {title && <p className="font-medium">{title}</p>}
        {children && <div className={cn(title && "mt-0.5", "text-[13px]")}>{children}</div>}
      </div>
      {action}
    </div>
  );
}

/**
 * Bao một query với đủ 4 trạng thái: Loading (skeleton), Error (có nút Thử lại), Empty, Success.
 * Lần tải lại khi đã có dữ liệu (đổi bộ lọc, trang) giữ dữ liệu cũ và hiện dải báo nếu lần tải lại thất bại.
 */
export function QueryBoundary<T>({
  query,
  isEmpty,
  skeleton,
  empty,
  children,
}: {
  query: UseQueryResult<T>;
  isEmpty?: (data: T) => boolean;
  skeleton?: ReactNode;
  empty?: ReactNode;
  children: (data: T) => ReactNode;
}) {
  if (query.isPending) return <>{skeleton ?? <TableSkeleton />}</>;
  if (query.isError && query.data === undefined) return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  const data = query.data as T;
  if (isEmpty?.(data)) return <>{empty}</>;
  return (
    <div className="space-y-3">
      {query.isError && (
        <Banner
          tone="warn"
          title="Không làm mới được dữ liệu"
          action={
            <Button size="sm" onClick={() => void query.refetch()}>
              Thử lại
            </Button>
          }
        >
          Đang hiển thị dữ liệu đã tải trước đó. {friendlyError(query.error)}
        </Banner>
      )}
      <div className={cn(query.isFetching && !query.isPending && "opacity-80 transition-opacity")}>{children(data)}</div>
    </div>
  );
}
