"use client";

import { useMemo, useState } from "react";
import { BottomSheet } from "@/components/ui/BottomSheet";
import { Button } from "@/components/ui/primitives";
import { Stepper } from "@/components/ui/Stepper";
import { env } from "@/lib/env";
import { formatVnd } from "@/lib/format";
import type { MenuOptionGroup, MenuProduct } from "@/lib/api/types";
import { useCartStore } from "@/stores/cart";

const NOTE_MAX = 200;

/** Số lựa chọn tối thiểu/tối đa hợp lệ của nhóm (maxSelect null = không giới hạn). */
function bounds(g: MenuOptionGroup) {
  const min = g.required ? Math.max(1, g.minSelect) : g.minSelect;
  const max = g.type === "SINGLE" ? 1 : (g.maxSelect ?? Number.POSITIVE_INFINITY);
  return { min, max };
}

export function OptionSheet({ product, onClose }: { product: MenuProduct; onClose: () => void }) {
  const add = useCartStore((s) => s.add);
  const [selected, setSelected] = useState<Record<string, string[]>>({});
  const [note, setNote] = useState("");
  const [quantity, setQuantity] = useState(1);
  const [showErrors, setShowErrors] = useState(false);

  const errors = useMemo(() => {
    const out: Record<string, string> = {};
    for (const g of product.optionGroups) {
      const n = (selected[g.id] ?? []).length;
      const { min, max } = bounds(g);
      if (n < min) out[g.id] = min === 1 ? "Vui lòng chọn một mục" : `Chọn ít nhất ${min} mục`;
      else if (n > max) out[g.id] = `Chọn tối đa ${max} mục`;
    }
    return out;
  }, [product, selected]);

  const extra = product.optionGroups.reduce(
    (sum, g) => sum + g.options.filter((o) => (selected[g.id] ?? []).includes(o.id)).reduce((s, o) => s + o.priceDelta, 0),
    0,
  );
  const unit = product.price + extra;

  function toggle(g: MenuOptionGroup, optionId: string) {
    setSelected((cur) => {
      const now = cur[g.id] ?? [];
      if (g.type === "SINGLE") return { ...cur, [g.id]: now.includes(optionId) && !g.required ? [] : [optionId] };
      if (now.includes(optionId)) return { ...cur, [g.id]: now.filter((x) => x !== optionId) };
      const { max } = bounds(g);
      if (now.length >= max) return cur; // đã đủ số lượng tối đa
      return { ...cur, [g.id]: [...now, optionId] };
    });
  }

  function submit() {
    if (Object.keys(errors).length > 0) {
      setShowErrors(true);
      return;
    }
    const optionIds = product.optionGroups.flatMap((g) => selected[g.id] ?? []);
    const optionSummary = product.optionGroups
      .flatMap((g) => g.options.filter((o) => (selected[g.id] ?? []).includes(o.id)).map((o) => o.name))
      .join(", ");
    add({ productId: product.id, name: product.name, unitPrice: unit, quantity, note: note.trim(), optionIds, optionSummary });
    onClose();
  }

  return (
    <BottomSheet
      open
      onClose={onClose}
      title={product.name}
      footer={
        <div className="flex items-center justify-between gap-3">
          <Stepper value={quantity} min={1} max={env.maxItemQuantity} onChange={setQuantity} />
          <Button onClick={submit} className="flex-1">
            Thêm · {formatVnd(unit * quantity)}
          </Button>
        </div>
      }
    >
      {product.description ? <p className="mb-4 text-sm text-ink-2">{product.description}</p> : null}

      {product.optionGroups.map((g) => {
        const { min, max } = bounds(g);
        const hint = g.type === "SINGLE" ? (g.required ? "Chọn 1" : "Tùy chọn") : `${min > 0 ? `Chọn ít nhất ${min}` : "Tùy chọn"}${Number.isFinite(max) ? `, tối đa ${max}` : ""}`;
        const err = showErrors ? errors[g.id] : undefined;
        return (
          <fieldset key={g.id} className="mb-5">
            <legend className="mb-2 flex w-full items-baseline justify-between">
              <span className="text-[15px] font-semibold">{g.name}</span>
              <span className={`text-xs ${err ? "text-danger" : "text-ink-3"}`}>{err ?? hint}</span>
            </legend>
            <div className="divide-y divide-line rounded-card border border-line">
              {g.options.map((o) => {
                const checked = (selected[g.id] ?? []).includes(o.id);
                return (
                  <label key={o.id} className={`flex min-h-12 items-center gap-3 px-3 ${o.available ? "cursor-pointer" : "cursor-not-allowed opacity-40"}`}>
                    <input
                      type={g.type === "SINGLE" ? "radio" : "checkbox"}
                      name={g.id}
                      checked={checked}
                      disabled={!o.available}
                      onChange={() => toggle(g, o.id)}
                      className="size-4 accent-[var(--color-accent)]"
                    />
                    <span className="flex-1 text-[15px]">{o.name}{!o.available ? " (hết)" : ""}</span>
                    {o.priceDelta !== 0 ? <span className="text-sm text-ink-2">{o.priceDelta > 0 ? "+" : ""}{formatVnd(o.priceDelta)}</span> : null}
                  </label>
                );
              })}
            </div>
          </fieldset>
        );
      })}

      <label className="block">
        <span className="mb-1.5 block text-[15px] font-semibold">Ghi chú cho món</span>
        <textarea
          value={note}
          onChange={(e) => setNote(e.target.value.slice(0, NOTE_MAX))}
          rows={2}
          placeholder="Ví dụ: ít cay, không hành"
          className="w-full resize-none rounded-ctl border border-line-strong bg-bg p-3 text-[15px] outline-none focus:border-accent"
        />
        <span className="mt-1 block text-right text-xs text-ink-3">{note.length}/{NOTE_MAX}</span>
      </label>
    </BottomSheet>
  );
}
