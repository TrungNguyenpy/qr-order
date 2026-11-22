import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from "axios";
import { env } from "@/lib/env";
import { getDeviceId } from "@/lib/storage";
import { useSessionStore } from "@/stores/session";
import { toJoinResult, toTokenResult } from "./types";

/** Lỗi đã chuẩn hóa từ ApiResponse.error của backend. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly details?: unknown,
    /** Giây chờ (header Retry-After) khi bị 429. */
    public readonly retryAfter?: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

interface Envelope<T> {
  success: boolean;
  data?: T;
  error?: { code: string; message: string; details?: unknown };
}

export const http = axios.create({
  baseURL: `${env.apiUrl}/api/v1`,
  timeout: 15_000,
  headers: { "Content-Type": "application/json" },
});

type AuthConfig = InternalAxiosRequestConfig & { skipAuth?: boolean; _retried?: boolean };

http.interceptors.request.use((config: AuthConfig) => {
  if (!config.skipAuth) {
    const token = useSessionStore.getState().token;
    if (token) config.headers.set("Authorization", `Bearer ${token}`);
  }
  return config;
});

/** Chỉ một lần gia hạn tại một thời điểm; các request 401 khác chờ cùng một promise. */
let refreshing: Promise<string | null> | null = null;

export function refreshGuestToken(): Promise<string | null> {
  if (refreshing) return refreshing;
  refreshing = doRefresh().finally(() => {
    refreshing = null;
  });
  return refreshing;
}

async function doRefresh(): Promise<string | null> {
  const store = useSessionStore.getState();
  if (!store.token && !store.qrToken) return null;

  // 1) Gia hạn bằng JWT hiện tại.
  if (store.token) {
    try {
      const res = await http.post("/guest/session/token", undefined, { skipAuth: false, _retried: true } as AxiosRequestConfig);
      const token = toTokenResult(res.data?.data).token;
      if (token) {
        useSessionStore.getState().setToken(token);
        return token;
      }
    } catch (e) {
      if (isSessionClosed(e)) return null; // đã được interceptor xử lý
    }
  }

  // 2) JWT hết hạn hẳn: vào lại bằng qrToken + deviceId (cùng deviceId nên trả về cùng phiên).
  if (store.qrToken) {
    try {
      const res = await http.post(
        "/public/sessions/join",
        { qrToken: store.qrToken, deviceId: getDeviceId() },
        { skipAuth: true, _retried: true } as AxiosRequestConfig,
      );
      const j = toJoinResult(res.data?.data);
      if (j.token) {
        useSessionStore.getState().setSession({
          token: j.token,
          sessionId: j.sessionId || store.sessionId,
          canOrder: j.canOrder,
          sessionStatus: j.sessionStatus,
          phase: "active",
        });
        return j.token;
      }
    } catch {
      /* rơi xuống null */
    }
  }
  return null;
}

function isSessionClosed(e: unknown): boolean {
  return e instanceof ApiError && e.code === "SESSION_CLOSED";
}

function toApiError(err: AxiosError<Envelope<unknown>>): ApiError {
  if (!err.response) {
    return new ApiError(0, "NETWORK_ERROR", "Không thể kết nối tới máy chủ. Vui lòng kiểm tra mạng và thử lại.");
  }
  const { status, data, headers } = err.response;
  const retryAfter = Number(headers?.["retry-after"]);
  return new ApiError(
    status,
    data?.error?.code ?? `HTTP_${status}`,
    data?.error?.message ?? "Đã có lỗi xảy ra. Vui lòng thử lại.",
    data?.error?.details,
    Number.isFinite(retryAfter) ? retryAfter : undefined,
  );
}

http.interceptors.response.use(
  (res) => {
    const body = res.data as Envelope<unknown> | undefined;
    if (body && typeof body === "object" && "success" in body && body.success === false) {
      throw new ApiError(res.status, body.error?.code ?? "UNKNOWN", body.error?.message ?? "Đã có lỗi xảy ra.", body.error?.details);
    }
    return res;
  },
  async (err: AxiosError<Envelope<unknown>>) => {
    const apiErr = toApiError(err);
    const config = err.config as AuthConfig | undefined;

    if (apiErr.status === 401) {
      if (apiErr.code === "SESSION_CLOSED") {
        useSessionStore.getState().markClosed();
        throw apiErr;
      }
      // JWT hết hạn/không hợp lệ: gia hạn rồi thử lại đúng một lần.
      if (config && !config.skipAuth && !config._retried) {
        const token = await refreshGuestToken();
        if (token) {
          config._retried = true;
          config.headers.set("Authorization", `Bearer ${token}`);
          return http.request(config);
        }
        if (useSessionStore.getState().phase !== "closed") {
          useSessionStore.getState().clear();
        }
      }
    }
    throw apiErr;
  },
);

/** Gọi API và bóc `data` khỏi ApiResponse. */
export async function call<T = unknown>(config: AxiosRequestConfig & { skipAuth?: boolean }): Promise<{ data: T; status: number }> {
  const res = await http.request<Envelope<T>>(config);
  return { data: res.data?.data as T, status: res.status };
}

export function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Đã có lỗi xảy ra. Vui lòng thử lại.";
}
