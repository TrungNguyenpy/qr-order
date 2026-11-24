"use client";

import { MutationCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";
import { ApiError } from "@/lib/api/client";
import { Toaster } from "@/components/ui/Toaster";
import { AUTH_STORAGE_KEY, useAuthStore } from "@/stores/auth";

function makeClient(): QueryClient {
  const client: QueryClient = new QueryClient({
    mutationCache: new MutationCache({
      onError: (error) => {
        // 409 CONCURRENT_MODIFICATION: dữ liệu trên màn hình đã cũ, tải lại mọi truy vấn đang hiển thị.
        if (error instanceof ApiError && error.code === "CONCURRENT_MODIFICATION") void client.invalidateQueries();
      },
    }),
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        // Không thử lại lỗi 4xx (sai quyền, không tìm thấy...); lỗi mạng/5xx thử lại một lần.
        retry: (count, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && count < 1,
      },
    },
  });
  return client;
}

export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(makeClient);

  // Đồng bộ đăng nhập/đăng xuất/làm mới token giữa các tab.
  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key === AUTH_STORAGE_KEY) void useAuthStore.persist.rehydrate();
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  return (
    <QueryClientProvider client={client}>
      {children}
      <Toaster />
    </QueryClientProvider>
  );
}
