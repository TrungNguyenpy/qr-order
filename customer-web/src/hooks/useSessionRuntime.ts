"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { refreshGuestToken } from "@/lib/api/client";
import { createRealtime, type RealtimeStatus } from "@/lib/realtime/stomp";
import { jwtExpiryMs } from "@/lib/storage";
import { useSessionStore } from "@/stores/session";

const REFRESH_MARGIN_MS = 60_000;

function eventType(e: unknown): string {
  if (e && typeof e === "object") {
    const o = e as Record<string, unknown>;
    const t = o.type ?? o.eventType;
    if (typeof t === "string") return t;
  }
  return "";
}
function eventField(e: unknown, key: string): string {
  return e && typeof e === "object" && typeof (e as Record<string, unknown>)[key] === "string" ? ((e as Record<string, string>)[key] as string) : "";
}

/**
 * Vòng đời phiên chạy nền trong layout (app):
 *  - Tự gia hạn Guest JWT trước khi hết hạn.
 *  - Kết nối STOMP, quy sự kiện thành "tín hiệu làm mới" (invalidate query) — REST mới là nguồn sự thật.
 */
export function useSessionRuntime(): RealtimeStatus {
  const qc = useQueryClient();
  const token = useSessionStore((s) => s.token);
  const sessionId = useSessionStore((s) => s.sessionId);
  const phase = useSessionStore((s) => s.phase);
  const [status, setStatus] = useState<RealtimeStatus>("idle");
  const lastMenuEvent = useRef(0);

  // Gia hạn JWT.
  useEffect(() => {
    if (phase !== "active" || !token) return;
    const exp = jwtExpiryMs(token);
    if (!exp) return;
    const delay = Math.max(5_000, exp - Date.now() - REFRESH_MARGIN_MS);
    const t = setTimeout(() => void refreshGuestToken(), delay);
    return () => clearTimeout(t);
  }, [token, phase]);

  // Realtime: kết nối lại chỉ khi đổi phiên (không kết nối lại mỗi lần JWT gia hạn).
  useEffect(() => {
    if (phase !== "active" || !sessionId) return;
    const current = useSessionStore.getState().token;
    if (!current) return;

    const rt = createRealtime(sessionId, current, {
      onStatus: setStatus,
      onResync: () => {
        void qc.invalidateQueries({ queryKey: ["menu"] });
        void qc.invalidateQueries({ queryKey: ["orders"] });
        void qc.invalidateQueries({ queryKey: ["bill"] });
      },
      onMenu: (e) => {
        // eventId tăng dần: nếu bị hụt số, vẫn chỉ cần tải lại menu (REST).
        const id = Number(eventField(e, "eventId") || (e as { eventId?: number } | null)?.eventId || 0);
        if (id) lastMenuEvent.current = id;
        void qc.invalidateQueries({ queryKey: ["menu"] });
      },
      onSession: (e) => {
        const st = useSessionStore.getState();
        switch (eventType(e)) {
          case "ORDER_CHANGED":
            void qc.invalidateQueries({ queryKey: ["orders"] });
            break;
          case "BILL_CHANGED":
            void qc.invalidateQueries({ queryKey: ["bill"] });
            break;
          case "SERVICE_CALL_CHANGED":
            void qc.invalidateQueries({ queryKey: ["serviceCalls"] });
            break;
          case "SESSION_CHANGED": {
            const change = eventField(e, "change") || eventField(e, "action") || eventField(e, "status");
            if (change === "CLOSED") st.markClosed();
            else if (change === "BILLING_STARTED" || change === "BILLING") st.setSession({ canOrder: false, sessionStatus: "BILLING" });
            else if (change === "REOPENED" || change === "OPENED") st.setSession({ canOrder: true, sessionStatus: "OPEN" });
            void qc.invalidateQueries({ queryKey: ["orders"] });
            void qc.invalidateQueries({ queryKey: ["bill"] });
            break;
          }
        }
      },
    });
    return () => rt.stop();
  }, [phase, sessionId, qc]);

  return status;
}
