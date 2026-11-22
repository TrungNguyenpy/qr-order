import { useSyncExternalStore } from "react";
import { create } from "zustand";
import { persist } from "zustand/middleware";

export type SessionPhase = "none" | "active" | "closed";

interface SessionState {
  /** Mã QR đã dùng để vào phiên: dùng để re-join khi JWT hết hạn hẳn. */
  qrToken: string | null;
  token: string | null;
  sessionId: string | null;
  tableName: string;
  pinRequired: boolean;
  canOrder: boolean;
  sessionStatus: string;
  phase: SessionPhase;

  setSession: (s: Partial<SessionState>) => void;
  setToken: (token: string) => void;
  /** Phiên đã đóng (401 SESSION_CLOSED / SESSION_CHANGED CLOSED): xóa token, giữ qrToken để khách biết bàn nào. */
  markClosed: () => void;
  clear: () => void;
}

const initial = {
  qrToken: null,
  token: null,
  sessionId: null,
  tableName: "",
  pinRequired: false,
  canOrder: true,
  sessionStatus: "OPEN",
  phase: "none" as SessionPhase,
};

export const useSessionStore = create<SessionState>()(
  persist(
    (set) => ({
      ...initial,
      setSession: (s) => set(s),
      setToken: (token) => set({ token }),
      markClosed: () => set({ token: null, phase: "closed", sessionStatus: "CLOSED", canOrder: false }),
      clear: () => set({ ...initial }),
    }),
    {
      name: "qo.session",
      partialize: (s) => ({
        qrToken: s.qrToken,
        token: s.token,
        sessionId: s.sessionId,
        tableName: s.tableName,
        pinRequired: s.pinRequired,
        canOrder: s.canOrder,
        sessionStatus: s.sessionStatus,
        phase: s.phase,
      }),
    },
  ),
);

/** Đã nạp xong dữ liệu từ localStorage chưa (server luôn trả false để khớp hydration). */
export function useSessionHydrated(): boolean {
  return useSyncExternalStore(
    (cb) => useSessionStore.persist.onFinishHydration(cb),
    () => useSessionStore.persist.hasHydrated(),
    () => false,
  );
}
