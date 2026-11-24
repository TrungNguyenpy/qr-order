import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from "axios";
import { AxiosError } from "axios";
import { beforeEach, describe, expect, it } from "vitest";
import type { CurrentUser } from "./types";
import { useAuthStore } from "@/stores/auth";
import { api, ApiError, http, refreshAccessToken } from "./client";

const USER: CurrentUser = { id: 1, username: "a", fullName: "A", role: "ADMIN", status: "ACTIVE", permissions: [], lastLoginAt: null, passwordChangedAt: null };

function ok(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { data: { success: true, data }, status, statusText: "OK", headers: {}, config };
}
function fail(config: InternalAxiosRequestConfig, status: number, code: string, message = code): Promise<never> {
  const response: AxiosResponse = { data: { success: false, error: { code, message } }, status, statusText: "", headers: {}, config };
  return Promise.reject(new AxiosError(message, String(status), config, null, response));
}

const calls: string[] = [];
let refreshCount = 0;
let refreshBehavior: (config: InternalAxiosRequestConfig) => Promise<AxiosResponse>;

function install(handler: AxiosAdapter) {
  http.defaults.adapter = handler;
}

beforeEach(() => {
  calls.length = 0;
  refreshCount = 0;
  useAuthStore.setState({ accessToken: "old", refreshToken: "r1", user: { ...USER }, logoutReason: null });
  refreshBehavior = async (config) => {
    refreshCount += 1;
    await new Promise((r) => setTimeout(r, 20));
    return ok(config, { accessToken: "new", refreshToken: "r2", tokenType: "Bearer", expiresIn: 900, user: { ...USER } });
  };
  install(async (config) => {
    calls.push(`${config.method} ${config.url}`);
    if (config.url === "/auth/refresh") return refreshBehavior(config);
    const auth = String(config.headers.get("Authorization"));
    if (auth !== "Bearer new") return fail(config, 401, "TOKEN_EXPIRED");
    return ok(config, { hello: config.url });
  });
});

describe("refresh token (BE-06)", () => {
  it("nhiều request 401 đồng thời chỉ gọi /auth/refresh MỘT lần rồi thử lại", async () => {
    const results = await Promise.all([api.get("/a"), api.get("/b"), api.get("/c")]);
    expect(results).toHaveLength(3);
    expect(refreshCount).toBe(1);
    expect(useAuthStore.getState().accessToken).toBe("new");
    expect(useAuthStore.getState().refreshToken).toBe("r2");
  });

  it("refresh 401: đăng xuất cục bộ kèm lý do và trả lỗi", async () => {
    refreshBehavior = (config) => {
      refreshCount += 1;
      return fail(config, 401, "TOKEN_INVALID");
    };
    await expect(api.get("/a")).rejects.toBeInstanceOf(ApiError);
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().logoutReason).toMatch(/hết hạn/i);
  });

  it("lỗi mạng/5xx khi refresh KHÔNG đăng xuất", async () => {
    refreshBehavior = (config) => {
      refreshCount += 1;
      return fail(config, 503, "SERVICE_UNAVAILABLE");
    };
    await expect(api.get("/a")).rejects.toMatchObject({ status: 503 });
    expect(useAuthStore.getState().refreshToken).toBe("r1");
    expect(useAuthStore.getState().logoutReason).toBeNull();
  });

  it("INVALID_CREDENTIALS không kích hoạt refresh", async () => {
    install(async (config) => {
      calls.push(`${config.method} ${config.url}`);
      return fail(config, 401, "INVALID_CREDENTIALS");
    });
    await expect(api.post("/auth/login", {})).rejects.toMatchObject({ code: "INVALID_CREDENTIALS" });
    expect(calls).toEqual(["post /auth/login"]);
  });

  it("403 REAUTHENTICATION_FAILED không đăng xuất và không refresh", async () => {
    useAuthStore.setState({ accessToken: "new" });
    install(async (config) => fail(config, 403, "REAUTHENTICATION_FAILED"));
    await expect(api.post("/users/1/role", {})).rejects.toMatchObject({ status: 403, code: "REAUTHENTICATION_FAILED" });
    expect(useAuthStore.getState().accessToken).toBe("new");
    expect(refreshCount).toBe(0);
  });

  it("chỉ thử lại một lần: vẫn 401 sau refresh thì trả lỗi", async () => {
    install(async (config) => {
      calls.push(`${config.method} ${config.url}`);
      if (config.url === "/auth/refresh") return ok(config, { accessToken: "new", refreshToken: "r2", tokenType: "Bearer", expiresIn: 900, user: { ...USER } });
      return fail(config, 401, "TOKEN_INVALID");
    });
    await expect(api.get("/a")).rejects.toMatchObject({ status: 401 });
    expect(calls.filter((c) => c === "post /auth/refresh")).toHaveLength(1);
    expect(calls.filter((c) => c === "get /a")).toHaveLength(2);
  });

  it("refreshAccessToken gọi đồng thời dùng chung một promise", async () => {
    const [a, b] = await Promise.all([refreshAccessToken("old"), refreshAccessToken("old")]);
    expect(a).toBe("new");
    expect(b).toBe("new");
    expect(refreshCount).toBe(1);
  });
});

describe("chuẩn hóa lỗi", () => {
  it("giữ code/message và chi tiết theo trường của backend", async () => {
    useAuthStore.setState({ accessToken: "new" });
    install(async (config) => {
      const response: AxiosResponse = {
        data: { success: false, error: { code: "VALIDATION_ERROR", message: "Dữ liệu không hợp lệ", details: [{ field: "name", message: "Bắt buộc" }] } },
        status: 400,
        statusText: "",
        headers: {},
        config,
      };
      return Promise.reject(new AxiosError("x", "400", config, null, response));
    });
    const err = (await api.post("/categories", {}).catch((e: unknown) => e)) as ApiError;
    expect(err.code).toBe("VALIDATION_ERROR");
    expect(err.details).toEqual([{ field: "name", message: "Bắt buộc" }]);
  });
});
