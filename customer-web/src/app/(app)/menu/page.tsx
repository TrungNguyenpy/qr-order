"use client";

import { useQuery } from "@tanstack/react-query";
import { useDeferredValue, useMemo, useState } from "react";
import { api } from "@/lib/api/endpoints";
import { friendlyError } from "@/lib/errors";
import { formatVnd } from "@/lib/format";
import type { MenuProduct } from "@/lib/api/types";
import { Badge, EmptyState, ErrorState, Skeleton } from "@/components/ui/primitives";
import { useSessionStore } from "@/stores/session";
import { OptionSheet } from "./OptionSheet";

/** Bỏ dấu tiếng Việt để tìm kiếm không phân biệt dấu. */
const fold = (s: string) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/g, "d").toLowerCase();

export default function MenuPage() {
  const q = useQuery({ queryKey: ["menu"], queryFn: api.menu });
  const canOrder = useSessionStore((s) => s.canOrder);
  const [cat, setCat] = useState<string>("all");
  const [search, setSearch] = useState("");
  const deferred = useDeferredValue(search);
  const [active, setActive] = useState<MenuProduct | null>(null);

  const products = useMemo(() => {
    const term = fold(deferred.trim());
    return (q.data?.products ?? []).filter((p) => (cat === "all" || p.categoryId === cat) && (!term || fold(p.name).includes(term)));
  }, [q.data, cat, deferred]);

  return (
    <div>
      <div className="sticky top-12 z-10 border-b border-line bg-bg px-4 pb-3 pt-3">
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Tìm món…"
          aria-label="Tìm món"
          className="h-10 w-full rounded-ctl border border-line-strong bg-surface px-3 text-[15px] outline-none focus:border-accent focus:bg-bg"
        />
        {q.data && q.data.categories.length > 0 ? (
          <div className="no-scrollbar -mx-4 mt-3 flex gap-2 overflow-x-auto px-4" role="tablist" aria-label="Danh mục">
            {[{ id: "all", name: "Tất cả" }, ...q.data.categories].map((c) => (
              <button
                key={c.id}
                role="tab"
                aria-selected={cat === c.id}
                onClick={() => setCat(c.id)}
                className={`h-8 shrink-0 rounded-full border px-3 text-sm font-medium ${cat === c.id ? "border-ink bg-ink text-bg" : "border-line-strong text-ink-2 hover:bg-surface"}`}
              >
                {c.name}
              </button>
            ))}
          </div>
        ) : null}
      </div>

      {q.isPending ? (
        <ul aria-busy="true" aria-label="Đang tải thực đơn" className="divide-y divide-line">
          {Array.from({ length: 6 }, (_, i) => (
            <li key={i} className="flex gap-3 px-4 py-3">
              <div className="flex-1 space-y-2">
                <Skeleton className="h-4 w-2/3" />
                <Skeleton className="h-3 w-full" />
                <Skeleton className="h-4 w-20" />
              </div>
              <Skeleton className="size-20" />
            </li>
          ))}
        </ul>
      ) : q.isError ? (
        <ErrorState message={friendlyError(q.error)} onRetry={() => void q.refetch()} />
      ) : products.length === 0 ? (
        <EmptyState title={deferred || cat !== "all" ? "Không tìm thấy món phù hợp" : "Thực đơn đang trống"} hint={deferred || cat !== "all" ? "Thử từ khóa khác hoặc chọn danh mục khác." : "Vui lòng quay lại sau."} />
      ) : (
        <ul className="divide-y divide-line">
          {products.map((p) => (
            <li key={p.id}>
              <button
                disabled={!p.available || !canOrder}
                onClick={() => setActive(p)}
                className={`flex w-full gap-3 px-4 py-3 text-left ${p.available && canOrder ? "hover:bg-surface" : "cursor-not-allowed"} ${p.available ? "" : "opacity-45"}`}
              >
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="truncate text-[15px] font-medium">{p.name}</span>
                    {!p.available ? <Badge>Hết món</Badge> : null}
                  </div>
                  {p.description ? <p className="mt-0.5 line-clamp-2 text-sm text-ink-2">{p.description}</p> : null}
                  <p className="mt-1.5 text-[15px] font-medium tabular-nums">{formatVnd(p.price)}</p>
                </div>
                {p.imageUrl ? (
                  // Ảnh Cloudinary: dùng <img> thường để không phải cấu hình remotePatterns cho từng cloud.
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={p.imageUrl} alt="" loading="lazy" className="size-20 shrink-0 rounded-ctl border border-line object-cover" />
                ) : null}
              </button>
            </li>
          ))}
        </ul>
      )}

      {active ? <OptionSheet key={active.id} product={active} onClose={() => setActive(null)} /> : null}
    </div>
  );
}
