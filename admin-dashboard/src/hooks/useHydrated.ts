"use client";

import { useSyncExternalStore } from "react";
import { useAuthStore } from "@/stores/auth";

/** true khi store `persist` đã nạp xong từ localStorage (tránh nháy màn hình đăng nhập khi tải lại trang). */
export function useAuthHydrated(): boolean {
  return useSyncExternalStore(
    (cb) => {
      const unsub = useAuthStore.persist.onFinishHydration(cb);
      return unsub;
    },
    () => useAuthStore.persist.hasHydrated(),
    () => false,
  );
}
