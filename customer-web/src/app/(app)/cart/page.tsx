"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { env } from "@/lib/env";
import { ApiError } from "@/lib/api/client";
import { api } from "@/lib/api/endpoints";
import { friendlyError } from "@/lib/errors";
import { formatVnd } from "@/lib/format";
import { Banner, Button, EmptyState } from "@/components/ui/primitives";
import { Stepper } from "@/components/ui/Stepper";
import { selectCartTotal, useCartStore } from "@/stores/cart";
import { useSessionStore } from "@/stores/session";

export default function CartPage() {
  const router = useRouter();
  const qc = useQueryClient();
  const lines = useCartStore((s) => s.lines);
  const total = useCartStore(selectCartTotal);
  const { setQuantity, remove, clear, ensureRequestId, resetRequestId } = useCartStore.getState();
  const canOrder = useSessionStore((s) => s.canOrder);

  const submit = useMutation({
    mutationFn: () =>
      // clientRequestId giữ nguyên khi retry (lỗi mạng) => server trả lại đợt cũ thay vì tạo trùng.
      api.placeBatch(
        ensureRequestId(),
        lines.map((l) => ({ productId: l.productId, quantity: l.quantity, note: l.note || undefined, optionItemIds: l.optionIds })),
      ),
    onSuccess: () => {
      clear();
      resetRequestId();
      void qc.invalidateQueries({ queryKey: ["orders"] });
      router.push("/orders");
    },
    onError: (e) => {
      // Lỗi nghiệp vụ (4xx) => lần gửi sau là một yêu cầu mới. Lỗi mạng/5xx => giữ requestId để retry an toàn.
      if (e instanceof ApiError && e.status >= 400 && e.status < 500) resetRequestId();
      if (e instanceof ApiError && e.code === "TABLE_PIN_REQUIRED") useSessionStore.getState().setSession({ pinRequired: true });
      if (e instanceof ApiError && (e.code === "PRODUCT_NOT_AVAILABLE" || e.code === "INVALID_OPTION_SELECTION")) void qc.invalidateQueries({ queryKey: ["menu"] });
    },
  });

  if (lines.length === 0) {
    return (
      <EmptyState
        title="Giỏ hàng trống"
        hint="Chọn món từ thực đơn để thêm vào giỏ."
        action={
          <Link href="/menu" className="inline-flex h-11 items-center rounded-ctl border border-line-strong px-4 text-[15px] font-medium hover:bg-surface">
            Xem thực đơn
          </Link>
        }
      />
    );
  }

  return (
    <div>
      <ul className="divide-y divide-line">
        {lines.map((l) => (
          <li key={l.key} className="flex items-start gap-3 px-4 py-3">
            <div className="min-w-0 flex-1">
              <p className="text-[15px] font-medium">{l.name}</p>
              {l.optionSummary ? <p className="text-sm text-ink-2">{l.optionSummary}</p> : null}
              {l.note ? <p className="text-sm text-ink-3">Ghi chú: {l.note}</p> : null}
              <p className="mt-1 text-[15px] tabular-nums">{formatVnd(l.unitPrice * l.quantity)}</p>
            </div>
            <div className="flex flex-col items-end gap-1">
              <Stepper value={l.quantity} min={0} max={env.maxItemQuantity} onChange={(v) => setQuantity(l.key, v)} />
              <button onClick={() => remove(l.key)} className="text-xs text-ink-3 underline">
                Xóa
              </button>
            </div>
          </li>
        ))}
      </ul>

      <div className="space-y-3 border-t border-line px-4 py-4">
        <div className="flex items-baseline justify-between">
          <span className="text-[15px] text-ink-2">Tạm tính (đã gồm VAT)</span>
          <span className="text-lg font-semibold tabular-nums">{formatVnd(total)}</span>
        </div>
        {!canOrder ? <Banner tone="warn">Bàn đang chờ thanh toán nên không thể gửi thêm món.</Banner> : null}
        {submit.isError ? <Banner tone="danger">{friendlyError(submit.error)}</Banner> : null}
        <Button className="w-full" disabled={!canOrder} loading={submit.isPending} onClick={() => submit.mutate()}>
          {submit.isError && !(submit.error instanceof ApiError && submit.error.status < 500 && submit.error.status > 0) ? "Thử gửi lại" : "Gửi gọi món"}
        </Button>
        <p className="text-center text-xs text-ink-3">Giá cuối cùng do nhà hàng xác nhận khi thanh toán.</p>
      </div>
    </div>
  );
}
