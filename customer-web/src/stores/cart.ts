import { create } from "zustand";
import { persist } from "zustand/middleware";
import { env } from "@/lib/env";
import { uuid } from "@/lib/format";

export interface CartLine {
  /** Khóa cục bộ: cùng sản phẩm + cùng option + cùng ghi chú thì gộp dòng. */
  key: string;
  productId: string;
  name: string;
  unitPrice: number; // giá gốc + phụ thu option (hiển thị; server tính lại khi đặt)
  quantity: number;
  note: string;
  optionIds: string[];
  optionSummary: string;
}

interface CartState {
  lines: CartLine[];
  /** UUID cho lần gửi hiện tại; giữ nguyên khi retry để chống gửi trùng, đổi khi gửi thành công. */
  pendingRequestId: string | null;
  add: (line: Omit<CartLine, "key">) => void;
  setQuantity: (key: string, quantity: number) => void;
  remove: (key: string) => void;
  clear: () => void;
  /** Lấy (hoặc tạo) clientRequestId cho lần gửi. */
  ensureRequestId: () => string;
  resetRequestId: () => void;
}

const lineKey = (l: Pick<CartLine, "productId" | "optionIds" | "note">) =>
  `${l.productId}|${[...l.optionIds].sort().join(",")}|${l.note.trim()}`;

export const useCartStore = create<CartState>()(
  persist(
    (set, get) => ({
      lines: [],
      pendingRequestId: null,
      add: (line) =>
        set((s) => {
          const key = lineKey(line);
          const existing = s.lines.find((l) => l.key === key);
          const lines = existing
            ? s.lines.map((l) =>
                l.key === key ? { ...l, quantity: Math.min(env.maxItemQuantity, l.quantity + line.quantity) } : l,
              )
            : [...s.lines, { ...line, key, quantity: Math.min(env.maxItemQuantity, line.quantity) }];
          // Giỏ đổi nội dung => lần gửi mới cần requestId mới.
          return { lines, pendingRequestId: null };
        }),
      setQuantity: (key, quantity) =>
        set((s) => ({
          lines:
            quantity <= 0
              ? s.lines.filter((l) => l.key !== key)
              : s.lines.map((l) => (l.key === key ? { ...l, quantity: Math.min(env.maxItemQuantity, quantity) } : l)),
          pendingRequestId: null,
        })),
      remove: (key) => set((s) => ({ lines: s.lines.filter((l) => l.key !== key), pendingRequestId: null })),
      clear: () => set({ lines: [], pendingRequestId: null }),
      ensureRequestId: () => {
        const cur = get().pendingRequestId;
        if (cur) return cur;
        const id = uuid();
        set({ pendingRequestId: id });
        return id;
      },
      resetRequestId: () => set({ pendingRequestId: null }),
    }),
    { name: "qo.cart" },
  ),
);

export const selectCartCount = (s: CartState) => s.lines.reduce((n, l) => n + l.quantity, 0);
export const selectCartTotal = (s: CartState) => s.lines.reduce((n, l) => n + l.quantity * l.unitPrice, 0);
