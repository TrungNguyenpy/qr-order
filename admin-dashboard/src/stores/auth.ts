import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { CurrentUser, TokenResponse } from "@/lib/api/types";

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  user: CurrentUser | null;
  /** Lý do bị đăng xuất cưỡng bức (hết phiên, tài khoản bị khóa...) để màn đăng nhập hiển thị. Không lưu bền. */
  logoutReason: string | null;
  setSession: (t: TokenResponse) => void;
  setUser: (u: CurrentUser) => void;
  clear: (reason?: string | null) => void;
}

export const AUTH_STORAGE_KEY = "qr-admin-auth";

/**
 * Token và thông tin user lưu ở localStorage để giữ đăng nhập qua các lần tải lại trang
 * (đánh đổi đã chấp nhận ở GĐ Admin: backend không dùng cookie HttpOnly). Không bao giờ ghi log token.
 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      logoutReason: null,
      setSession: (t) =>
        set({ accessToken: t.accessToken, refreshToken: t.refreshToken, user: t.user, logoutReason: null }),
      setUser: (user) => set({ user }),
      clear: (reason = null) => set({ accessToken: null, refreshToken: null, user: null, logoutReason: reason }),
    }),
    {
      name: AUTH_STORAGE_KEY,
      partialize: (s) => ({ accessToken: s.accessToken, refreshToken: s.refreshToken, user: s.user }),
    },
  ),
);
