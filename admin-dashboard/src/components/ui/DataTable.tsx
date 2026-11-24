import type { ReactNode } from "react";
import { cn } from "@/lib/cn";
import { Button } from "./Button";

export interface Column<T> {
  key: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
  className?: string;
  align?: "left" | "right" | "center";
}

const ALIGN = { left: "text-left", right: "text-right", center: "text-center" };

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  caption,
  rowClassName,
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string | number;
  caption: string;
  rowClassName?: (row: T) => string | undefined;
}) {
  return (
    <div className="overflow-x-auto rounded-card border border-line">
      <table className="w-full min-w-[640px] border-collapse text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className="border-b border-line bg-surface">
            {columns.map((c) => (
              <th
                key={c.key}
                scope="col"
                className={cn("px-4 py-2.5 text-xs font-medium uppercase tracking-wide text-ink-3", ALIGN[c.align ?? "left"], c.className)}
              >
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={rowKey(row)} className={cn("border-b border-line last:border-b-0 hover:bg-surface/60", rowClassName?.(row))}>
              {columns.map((c) => (
                <td key={c.key} className={cn("px-4 py-3 align-middle", ALIGN[c.align ?? "left"], c.className)}>
                  {c.cell(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Phân trang theo `Page` của backend (page tính từ 0). */
export function Pagination({
  page,
  totalPages,
  totalElements,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  onPage: (p: number) => void;
}) {
  if (totalElements === 0) return null;
  const last = Math.max(0, totalPages - 1);
  return (
    <nav className="mt-3 flex flex-wrap items-center justify-between gap-2 text-[13px] text-ink-2" aria-label="Phân trang">
      <span>
        {totalElements} bản ghi · Trang {page + 1}/{Math.max(1, totalPages)}
      </span>
      <div className="flex gap-2">
        <Button size="sm" disabled={page <= 0} onClick={() => onPage(0)} aria-label="Trang đầu">
          «
        </Button>
        <Button size="sm" disabled={page <= 0} onClick={() => onPage(page - 1)}>
          Trước
        </Button>
        <Button size="sm" disabled={page >= last} onClick={() => onPage(page + 1)}>
          Sau
        </Button>
        <Button size="sm" disabled={page >= last} onClick={() => onPage(last)} aria-label="Trang cuối">
          »
        </Button>
      </div>
    </nav>
  );
}
