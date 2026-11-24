import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from "axios";
import { env } from "@/lib/env";
import { useAuthStore } from "@/stores/auth";
import type { ErrorDetail, TokenResponse } from "./types";

/** Lỗi đã chuẩn hóa từ `ApiResponse.error` của backend (hoặc lỗi mạng: status = 0). */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly details: ErrorDetail[] = [],
    public readonly correlationId?: string,
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
  error?: { code?: string; message?: string; details?: unknown };
  correlationId?: string;
}

/** Cấu hình mở rộng cho từng request. */
export type RequestOptions = AxiosRequestConfig & {
  /** Không gắn Authorization và không tự refresh (login, refresh, logout). */
  skipAuth?: boolean;
};
type InternalConfig = InternalAxiosRequestConfig & { skipAuth?: boolean; _retried?: boolean };

export const http = axios.create({
  baseURL: `${env.apiUrl}/api/v1`,
  timeout: 20_000,
  headers: { "Content-Type": "application/json" },
});

http.interceptors.request.use((config: InternalConfig) => {
  if (!config.skipAuth) {
    const token = useAuthStore.getState().accessToken;
    if (token) config.headers.set("Authorization", `Bearer ${token}`);
  }
  return config;
});

// ---------------------------------------------------------------------------------------------- Chuẩn hóa lỗi

function normalizeDetails(raw: unknown): ErrorDetail[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .map((d): ErrorDetail | null => {
      if (!d || typeof d !== "object") return null;
      const o = d as Record<string, unknown>;
      return typeof o.message === "string"
        ? { field: typeof o.field === "string" ? o.field : undefined, message: o.message }
        : null;
    })
    .filter((d): d is ErrorDetail => d !== null);
}

/** Lỗi của request tải file (responseType blob) vẫn là JSON `ApiResponse`: đọc lại từ blob. */
async function readBody(data: unknown): Promise<Envelope<unknown> | null> {
  try {
    if (typeof Blob !== "undefined" && data instanceof Blob) {
      return JSON.parse(await data.text()) as Envelope<unknown>;
    }
    if (data && typeof data === "object") return data as Envelope<unknown>;
  } catch {
    /* không phải JSON */
  }
  return null;
}

export async function toApiError(error: unknown): Promise<ApiError> {
  if (error instanceof ApiError) return error;
  if (axios.isAxiosError(error)) {
    if (!error.response) {
      const timeout = error.code === "ECONNABORTED" || error.code === "ETIMEDOUT";
      return new ApiError(0, "NETWORK_ERROR", timeout ? "Máy chủ phản hồi quá lâu." : "Không thể kết nối tới máy chủ.");
    }
    const body = await readBody(error.response.data);
    const retry = Number(error.response.headers?.["retry-after"]);
    return new ApiError(
      error.response.status,
      body?.error?.code ?? `HTTP_${error.response.status}`,
      body?.error?.message ?? `Yêu cầu thất bại (HTTP ${error.response.status}).`,
      normalizeDetails(body?.error?.details),
      body?.correlationId,
      Number.isFinite(retry) && retry > 0 ? retry : undefined,
    );
  }
  return new ApiError(0, "UNKNOWN", error instanceof Error ? error.message : "Đã có lỗi xảy ra.");
}

// ---------------------------------------------------------------------------------------------- Refresh tuần tự

/**
 * Mã 401 mà refresh KHÔNG giúp được: đăng nhập sai, tài khoản bị khóa. Các mã còn lại
 * (TOKEN_EXPIRED, TOKEN_INVALID, UNAUTHENTICATED) thử làm mới access token đúng một lần.
 */
const NO_REFRESH_CODES = new Set(["INVALID_CREDENTIALS", "ACCOUNT_DISABLED"]);

const LOCK_NAME = "qr-admin-auth-refresh";

/** Khóa liên tab (Web Locks): hai tab không refresh cùng lúc bằng cùng một refresh token (BE-06). */
function withCrossTabLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== "undefined" ? navigator.locks : undefined;
  return locks ? locks.request(LOCK_NAME, fn) : fn();
}

let refreshing: Promise<string | null> | null = null;

/**
 * Làm mới access token. TẤT CẢ lời gọi (request 401 đồng thời, STOMP) dùng chung MỘT promise nên tại mỗi
 * thời điểm chỉ có tối đa một request `POST /auth/refresh` trong tab (BE-06: hai refresh song song cùng token
 * bị coi là tái sử dụng và thu hồi cả phiên đăng nhập).
 *
 * @param staleToken access token đã bị từ chối. Nếu sau khi đồng bộ từ localStorage token đã khác (tab khác vừa
 *   refresh xong) thì dùng luôn token mới, không refresh lần nữa.
 * @returns access token hợp lệ, hoặc null nếu phiên đã kết thúc (đã đăng xuất cục bộ).
 */
export function refreshAccessToken(staleToken: string | null = useAuthStore.getState().accessToken): Promise<string | null> {
  if (!refreshing) {
    refreshing = withCrossTabLock(() => runRefresh(staleToken)).finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

async function runRefresh(staleToken: string | null): Promise<string | null> {
  // Tab khác có thể đã xoay token: đọc lại từ localStorage trước khi quyết định.
  await useAuthStore.persist.rehydrate();
  const { accessToken, refreshToken, clear, setSession } = useAuthStore.getState();
  if (!refreshToken) {
    clear("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
    return null;
  }
  if (accessToken && staleToken && accessToken !== staleToken) return accessToken;

  try {
    const res = await http.post<Envelope<TokenResponse>>("/auth/refresh", { refreshToken }, { skipAuth: true } as RequestOptions);
    const token = res.data.data;
    if (!token?.accessToken) throw new ApiError(res.status, "INVALID_RESPONSE", "Phản hồi làm mới phiên không hợp lệ.");
    setSession(token);
    return token.accessToken;
  } catch (e) {
    const err = await toApiError(e);
    // 401 (token hết hạn/đã dùng/bị thu hồi, tài khoản bị khóa): kết thúc phiên. Lỗi mạng/5xx: giữ phiên, báo lỗi.
    if (err.status === 401) {
      clear(
        err.code === "ACCOUNT_DISABLED"
          ? "Tài khoản đã bị khóa hoặc ngừng sử dụng."
          : "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.",
      );
      return null;
    }
    throw err;
  }
}

/** Access token còn hạn (> 30 giây) hoặc vừa được làm mới; dùng cho kết nối STOMP. */
export async function getValidAccessToken(): Promise<string | null> {
  const token = useAuthStore.getState().accessToken;
  if (!token) return null;
  const exp = decodeJwtExp(token);
  if (exp !== null && exp - Date.now() / 1000 > 30) return token;
  return refreshAccessToken(token);
}

function decodeJwtExp(token: string): number | null {
  try {
    const payload = token.split(".")[1];
    if (!payload) return null;
    const json = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/"))) as { exp?: unknown };
    return typeof json.exp === "number" ? json.exp : null;
  } catch {
    return null;
  }
}

http.interceptors.response.use(undefined, async (error: unknown) => {
  const apiError = await toApiError(error);
  const config = axios.isAxiosError(error) ? (error as AxiosError).config : undefined;
  const cfg = config as InternalConfig | undefined;

  if (apiError.status === 401 && cfg && !cfg.skipAuth && !cfg._retried) {
    if (apiError.code === "ACCOUNT_DISABLED") {
      useAuthStore.getState().clear("Tài khoản đã bị khóa hoặc ngừng sử dụng.");
    } else if (!NO_REFRESH_CODES.has(apiError.code)) {
      const sent = String(cfg.headers?.get?.("Authorization") ?? "").replace(/^Bearer\s+/i, "") || null;
      const token = await refreshAccessToken(sent);
      if (token) {
        cfg._retried = true;
        return http.request(cfg);
      }
    }
  }
  // 403 (kể cả REAUTHENTICATION_FAILED) KHÔNG đăng xuất: để màn hình tự hiển thị lỗi.
  throw apiError;
});

// ---------------------------------------------------------------------------------------------- Helper gọi API

/** Gọi API và bóc `ApiResponse.data`. */
export async function request<T>(config: RequestOptions): Promise<T> {
  const res = await http.request<Envelope<T>>(config);
  const body = res.data;
  if (!body || body.success === false) {
    throw new ApiError(res.status, body?.error?.code ?? "INVALID_RESPONSE", body?.error?.message ?? "Phản hồi không hợp lệ.");
  }
  return body.data as T;
}

export const api = {
  get: <T>(url: string, params?: Record<string, unknown>) => request<T>({ method: "GET", url, params }),
  post: <T>(url: string, data?: unknown) => request<T>({ method: "POST", url, data }),
  put: <T>(url: string, data?: unknown) => request<T>({ method: "PUT", url, data }),
  patch: <T>(url: string, data?: unknown) => request<T>({ method: "PATCH", url, data }),
  delete: <T>(url: string, params?: Record<string, unknown>) => request<T>({ method: "DELETE", url, params }),
};

/** Tải file nhị phân (PNG/PDF) kèm Authorization. */
export async function downloadBlob(url: string, params?: Record<string, unknown>): Promise<{ blob: Blob; fileName: string | null }> {
  const res = await http.get<Blob>(url, {
    params,
    responseType: "blob",
    paramsSerializer: { indexes: null }, // tableIds=1&tableIds=2
  });
  const cd = String(res.headers?.["content-disposition"] ?? "");
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(cd);
  return { blob: res.data, fileName: match?.[1] ? decodeURIComponent(match[1]) : null };
}
