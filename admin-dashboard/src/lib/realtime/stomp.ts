"use client";

import { useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import { useQueryClient } from "@tanstack/react-query";
import { getValidAccessToken } from "@/lib/api/client";
import { env } from "@/lib/env";
import { useAuthStore } from "@/stores/auth";

export type RealtimeStatus = "connecting" | "connected" | "offline" | "polling";

/** Chu kỳ tải lại sơ đồ bàn khi không có realtime (ADMIN) hoặc khi mất kết nối. */
export const POLL_INTERVAL_MS = 30_000;

const TOPICS = ["/topic/tables", "/topic/staff/orders"] as const;

/**
 * Realtime cho sơ đồ bàn qua STOMP.
 *
 * - Backend chỉ cho STAFF/MANAGER subscribe `/topic/tables` và `/topic/staff/orders`; ADMIN bị từ chối,
 *   nên ADMIN không mở kết nối mà dùng polling (trạng thái `polling`).
 * - Token luôn lấy qua `getValidAccessToken()` trong `beforeConnect` để mỗi lần (tự) kết nối lại dùng token còn hạn.
 * - Mỗi khi có sự kiện, và mỗi khi (kết nối lại) thành công, tải lại `floor-plan` / `tables` bằng REST
 *   (backend khuyến nghị tải lại sau khi mất kết nối vì STOMP không phát lại sự kiện đã lỡ).
 */
export function useTableRealtime(): RealtimeStatus {
  const qc = useQueryClient();
  const role = useAuthStore((s) => s.user?.role);
  const canSubscribe = role === "MANAGER" && !!env.wsUrl; // ADMIN: không được subscribe; thiếu WS URL: chỉ polling
  const [status, setStatus] = useState<RealtimeStatus>("connecting");
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    if (!canSubscribe) return;
    let disposed = false;
    const refresh = () => {
      // Sự kiện chỉ là tín hiệu: tải lại mọi dữ liệu vận hành đang hiển thị bằng REST.
      for (const key of ["floor-plan", "tables", "sessions", "session-order", "bill", "cancel-requests", "billing-attention"]) {
        void qc.invalidateQueries({ queryKey: [key] });
      }
    };

    const client = new Client({
      brokerURL: env.wsUrl,
      reconnectDelay: 5000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      beforeConnect: async () => {
        const token = await getValidAccessToken();
        if (disposed) return;
        if (!token) {
          // Hết phiên đăng nhập: dừng kết nối, AppShell sẽ chuyển về trang đăng nhập.
          setStatus("offline");
          await client.deactivate();
          return;
        }
        client.connectHeaders = { Authorization: `Bearer ${token}` };
        setStatus("connecting");
      },
      onConnect: () => {
        setStatus("connected");
        for (const t of TOPICS) client.subscribe(t, refresh);
        refresh(); // đồng bộ lại những gì có thể đã lỡ trong lúc mất kết nối
      },
      onWebSocketClose: () => {
        if (!disposed) setStatus("offline");
      },
      onStompError: () => {
        if (!disposed) setStatus("offline");
      },
    });
    clientRef.current = client;
    client.activate();

    return () => {
      disposed = true;
      clientRef.current = null;
      void client.deactivate();
    };
  }, [canSubscribe, qc]);

  return canSubscribe ? status : "polling";
}
