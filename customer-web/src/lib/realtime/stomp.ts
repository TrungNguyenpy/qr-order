import { Client, type IMessage, type StompConfig, type StompSubscription } from "@stomp/stompjs";
import { env } from "@/lib/env";

export type RealtimeStatus = "idle" | "connecting" | "connected" | "disconnected";

export interface RealtimeHandlers {
  onMenu: (event: unknown) => void;
  onSession: (event: unknown) => void;
  /** Gọi lần đầu kết nối và mỗi lần kết nối lại: sự kiện chỉ là tín hiệu, cần đồng bộ lại bằng REST. */
  onResync: () => void;
  onStatus: (s: RealtimeStatus) => void;
}

function parse(msg: IMessage): unknown {
  try {
    return JSON.parse(msg.body);
  } catch {
    return null;
  }
}

/**
 * Kết nối STOMP. ws(s):// -> WebSocket thuần (khuyến nghị, `/ws/websocket`);
 * http(s):// -> SockJS (`/ws`), chỉ nạp thư viện SockJS khi thực sự dùng (tránh cảnh báo `unload` của trình duyệt).
 */
export function createRealtime(sessionId: string, token: string, h: RealtimeHandlers): { stop: () => void } {
  const url = env.wsUrl;
  if (!url) {
    h.onStatus("disconnected");
    return { stop: () => undefined };
  }
  const raw = /^wss?:\/\//i.test(url);
  const subs: StompSubscription[] = [];
  let client: Client | null = null;
  let stopped = false;

  const start = (transport: Partial<StompConfig>) => {
    if (stopped) return;
    const c: Client = new Client({
      ...transport,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 3000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      onConnect: () => {
        h.onStatus("connected");
        subs.push(c.subscribe("/topic/menu", (m) => h.onMenu(parse(m))));
        subs.push(c.subscribe(`/topic/session/${sessionId}`, (m) => h.onSession(parse(m))));
        // Lần đầu và mọi lần reconnect đều đồng bộ lại để không lỡ sự kiện.
        h.onResync();
      },
      onWebSocketClose: () => {
        subs.length = 0;
        if (!stopped) h.onStatus("connecting");
      },
      onStompError: () => h.onStatus("disconnected"),
    });
    client = c;
    c.activate();
  };

  h.onStatus("connecting");
  if (raw) {
    start({ brokerURL: url });
  } else {
    void import("sockjs-client").then((m) => start({ webSocketFactory: () => new m.default(url) as unknown as WebSocket }));
  }

  return {
    stop: () => {
      stopped = true;
      subs.forEach((s) => s.unsubscribe());
      if (client) void client.deactivate();
      h.onStatus("idle");
    },
  };
}
