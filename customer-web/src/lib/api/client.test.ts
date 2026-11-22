import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from "axios";
import { beforeEach, describe, expect, it } from "vitest";
import { useSessionStore } from "@/stores/session";
import { getDeviceId } from "@/lib/storage";
import { ApiError, call, http, refreshGuestToken } from "./client";

/**
 * Giả lập backend bằng adapter của Axios (không mở mạng thật), để kiểm tra đúng hành vi BE-06:
 * một lần gia hạn tại một thời điểm, fallback re-join, SESSION_CLOSED.
 */
interface Recorded {
  method: string;
  url: string;
  auth: string | undefined;
  body: unknown;
}
type Reply = { status: number; data?: unknown; headers?: Record<string, string> } | "network-error";
type Handler = (req: Recorded) => Reply | Promise<Reply>;

let calls: Recorded[] = [];
const countOf = (url: string) => calls.filter((c) => c.url === url).length;

function install(handler: Handler) {
  http.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
    const req: Recorded = {
      method: (config.method ?? "get").toUpperCase(),
      url: config.url ?? "",
      auth: config.headers.get("Authorization")?.toString(),
      body: typeof config.data === "string" ? JSON.parse(config.data) : config.data,
    };
    calls.push(req);
    const reply = await handler(req);
    if (reply === "network-error") throw new AxiosError("Network Error", "ERR_NETWORK", config);
    const response = { data: reply.data, status: reply.status, statusText: "", headers: new AxiosHeaders(reply.headers), config };
    if (reply.status >= 400) throw new AxiosError("fail", "ERR_BAD_REQUEST", config, null, response);
    return response;
  };
}

const ok = (data: unknown, status = 200): Reply => ({ status, data: { success: true, data } });
const fail = (status: number, code: string, extra: { headers?: Record<string, string> } = {}): Reply => ({
  status,
  data: { success: false, error: { code, message: `msg ${code}` } },
  ...extra,
});

const OLD = "old.jwt";
const NEW = "new.jwt";
const joinBody = (token: string) => ({
  accessToken: token,
  session: { sessionId: "sess-1", status: "OPEN", canOrder: true, table: { displayName: "Bàn 1" } },
});

function startSession(over: Record<string, unknown> = {}) {
  useSessionStore.setState({
    qrToken: "dev-qr-T01",
    token: OLD,
    sessionId: "sess-1",
    tableName: "Bàn 1",
    phase: "active",
    sessionStatus: "OPEN",
    canOrder: true,
    ...over,
  });
}

beforeEach(() => {
  calls = [];
  useSessionStore.getState().clear();
  startSession();
});

describe("gia hạn Guest JWT tuần tự một luồng (BE-06)", () => {
  it("nhiều request 401 đồng thời chỉ kích hoạt đúng 1 request gia hạn rồi thử lại tất cả", async () => {
    install((req) => {
      if (req.url === "/guest/session/token") return ok({ accessToken: NEW });
      return req.auth === `Bearer ${NEW}` ? ok({ path: req.url }) : fail(401, "TOKEN_EXPIRED");
    });

    const results = await Promise.all([
      call({ url: "/guest/orders" }),
      call({ url: "/guest/bill" }),
      call({ url: "/public/menu" }),
      call({ url: "/guest/service-calls", method: "POST" }),
    ]);

    expect(countOf("/guest/session/token")).toBe(1);
    expect(results.map((r) => r.status)).toEqual([200, 200, 200, 200]);
    expect(useSessionStore.getState().token).toBe(NEW);
    // Mỗi request gốc: 1 lần với JWT cũ (401) + 1 lần thử lại với JWT mới.
    for (const url of ["/guest/orders", "/guest/bill", "/public/menu", "/guest/service-calls"]) {
      expect(calls.filter((c) => c.url === url).map((c) => c.auth)).toEqual([`Bearer ${OLD}`, `Bearer ${NEW}`]);
    }
    expect(countOf("/public/sessions/join")).toBe(0);
  });

  it("gọi refreshGuestToken đồng thời dùng chung một promise", async () => {
    install(async (req) => {
      await new Promise((r) => setTimeout(r, 10));
      return req.url === "/guest/session/token" ? ok({ accessToken: NEW }) : fail(404, "NOT_FOUND");
    });
    const [a, b, c] = await Promise.all([refreshGuestToken(), refreshGuestToken(), refreshGuestToken()]);
    expect([a, b, c]).toEqual([NEW, NEW, NEW]);
    expect(countOf("/guest/session/token")).toBe(1);
  });

  it("sau khi một lần gia hạn xong, lần gia hạn sau là một request mới", async () => {
    let n = 0;
    install((req) => (req.url === "/guest/session/token" ? ok({ accessToken: `jwt-${++n}` }) : fail(404, "NOT_FOUND")));
    expect(await refreshGuestToken()).toBe("jwt-1");
    expect(await refreshGuestToken()).toBe("jwt-2");
    expect(countOf("/guest/session/token")).toBe(2);
  });

  it("request đã thử lại mà vẫn 401 thì không lặp vô hạn", async () => {
    install((req) => (req.url === "/guest/session/token" ? ok({ accessToken: NEW }) : fail(401, "TOKEN_EXPIRED")));
    await expect(call({ url: "/guest/orders" })).rejects.toMatchObject({ status: 401 });
    expect(countOf("/guest/orders")).toBe(2);
    expect(countOf("/guest/session/token")).toBe(1);
  });
});

describe("fallback re-join khi JWT hết hạn hẳn", () => {
  it("gia hạn thất bại thì re-join bằng qrToken + deviceId, rồi thử lại request gốc", async () => {
    install((req) => {
      if (req.url === "/guest/session/token") return fail(401, "TOKEN_EXPIRED");
      if (req.url === "/public/sessions/join") return ok(joinBody(NEW));
      return req.auth === `Bearer ${NEW}` ? ok({ done: true }) : fail(401, "TOKEN_EXPIRED");
    });

    const res = await call({ url: "/guest/orders" });

    expect(res.status).toBe(200);
    const join = calls.find((c) => c.url === "/public/sessions/join");
    expect(join?.body).toEqual({ qrToken: "dev-qr-T01", deviceId: getDeviceId() });
    expect(join?.auth).toBeUndefined(); // join không gửi Authorization
    const s = useSessionStore.getState();
    expect(s).toMatchObject({ token: NEW, sessionId: "sess-1", phase: "active", sessionStatus: "OPEN" });
  });

  it("re-join dùng chung một luồng khi nhiều request 401 cùng lúc", async () => {
    install((req) => {
      if (req.url === "/guest/session/token") return fail(401, "TOKEN_EXPIRED");
      if (req.url === "/public/sessions/join") return ok(joinBody(NEW));
      return req.auth === `Bearer ${NEW}` ? ok({}) : fail(401, "TOKEN_EXPIRED");
    });
    await Promise.all([call({ url: "/guest/orders" }), call({ url: "/guest/bill" })]);
    expect(countOf("/guest/session/token")).toBe(1);
    expect(countOf("/public/sessions/join")).toBe(1);
  });

  it("không có JWT nhưng còn qrToken thì vào thẳng bước re-join", async () => {
    startSession({ token: null });
    install((req) => (req.url === "/public/sessions/join" ? ok(joinBody(NEW)) : fail(404, "NOT_FOUND")));
    expect(await refreshGuestToken()).toBe(NEW);
    expect(countOf("/guest/session/token")).toBe(0);
  });

  it("gia hạn và re-join đều thất bại thì xóa phiên và request gốc lỗi 401", async () => {
    install((req) => {
      if (req.url === "/guest/session/token") return fail(401, "TOKEN_EXPIRED");
      if (req.url === "/public/sessions/join") return fail(404, "QR_NOT_FOUND");
      return fail(401, "TOKEN_EXPIRED");
    });

    await expect(call({ url: "/guest/orders" })).rejects.toMatchObject({ status: 401 });

    const s = useSessionStore.getState();
    expect(s.phase).toBe("none");
    expect(s.token).toBeNull();
    expect(s.sessionId).toBeNull();
  });

  it("không có token lẫn qrToken thì không gọi mạng và trả null", async () => {
    startSession({ token: null, qrToken: null });
    install(() => fail(500, "SHOULD_NOT_BE_CALLED"));
    expect(await refreshGuestToken()).toBeNull();
    expect(calls).toHaveLength(0);
  });
});

describe("401 SESSION_CLOSED", () => {
  it("đánh dấu phiên đóng ngay, không gia hạn, không re-join", async () => {
    install(() => fail(401, "SESSION_CLOSED"));

    await expect(call({ url: "/guest/orders" })).rejects.toMatchObject({ status: 401, code: "SESSION_CLOSED" });

    const s = useSessionStore.getState();
    expect(s).toMatchObject({ phase: "closed", token: null, sessionStatus: "CLOSED", canOrder: false });
    expect(s.qrToken).toBe("dev-qr-T01"); // giữ để khách biết bàn nào
    expect(countOf("/guest/session/token")).toBe(0);
    expect(countOf("/public/sessions/join")).toBe(0);
    expect(countOf("/guest/orders")).toBe(1);
  });

  it("phiên đóng trong lúc gia hạn thì dừng, không re-join, giữ phase closed", async () => {
    install((req) => {
      if (req.url === "/guest/session/token") return fail(401, "SESSION_CLOSED");
      if (req.url === "/public/sessions/join") return ok(joinBody(NEW));
      return fail(401, "TOKEN_EXPIRED");
    });

    await expect(call({ url: "/guest/orders" })).rejects.toBeInstanceOf(ApiError);

    expect(countOf("/public/sessions/join")).toBe(0);
    expect(useSessionStore.getState().phase).toBe("closed");
  });
});

describe("chuẩn hóa lỗi", () => {
  it("429 mang Retry-After (giây)", async () => {
    install(() => fail(429, "RATE_LIMIT_EXCEEDED", { headers: { "retry-after": "12" } }));
    await expect(call({ url: "/guest/orders", method: "POST" })).rejects.toMatchObject({
      status: 429,
      code: "RATE_LIMIT_EXCEEDED",
      retryAfter: 12,
    });
  });

  it("mất mạng thành NETWORK_ERROR status 0", async () => {
    install(() => "network-error");
    await expect(call({ url: "/public/menu" })).rejects.toMatchObject({ status: 0, code: "NETWORK_ERROR" });
  });

  it("lỗi 4xx khác 401 giữ nguyên mã lỗi và không đụng tới phiên", async () => {
    install(() => fail(422, "INVALID_OPTION_SELECTION"));
    await expect(call({ url: "/guest/orders", method: "POST" })).rejects.toMatchObject({ status: 422, code: "INVALID_OPTION_SELECTION" });
    expect(useSessionStore.getState().phase).toBe("active");
    expect(countOf("/guest/session/token")).toBe(0);
  });

  it("gắn Bearer từ store vào request thường", async () => {
    install(() => ok({}));
    await call({ url: "/guest/orders" });
    expect(calls[0]?.auth).toBe(`Bearer ${OLD}`);
  });

  it("request skipAuth không gửi Authorization", async () => {
    install(() => ok({}));
    await call({ url: "/public/menu", skipAuth: true });
    expect(calls[0]?.auth).toBeUndefined();
  });
});
