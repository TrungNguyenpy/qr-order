import { api, request } from "./client";
import type { CurrentUser, TokenResponse } from "./types";

export const authApi = {
  login: (username: string, password: string) =>
    request<TokenResponse>({ method: "POST", url: "/auth/login", data: { username, password }, skipAuth: true }),
  me: () => api.get<CurrentUser>("/auth/me"),
  /** Luôn trả thành công ở backend; bỏ qua lỗi mạng khi đăng xuất. */
  logout: (refreshToken: string) =>
    request<void>({ method: "POST", url: "/auth/logout", data: { refreshToken }, skipAuth: true }),
  changePassword: (currentPassword: string, newPassword: string) =>
    api.post<void>("/auth/change-password", { currentPassword, newPassword }),
};
