import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api/client";
import type { OrderConfirmationMode, SettingsDto } from "@/lib/api/types";

export const SETTINGS_KEY = ["settings"] as const;

export function useSettings() {
  return useQuery({ queryKey: SETTINGS_KEY, queryFn: () => api.get<SettingsDto>("/settings") });
}

/** Thân PUT /settings: thay thế toàn bộ trường cho phép sửa + `version` đã đọc (optimistic locking). */
export interface UpdateSettingsInput {
  vatRate: number | null;
  serviceFeeRate: number;
  serviceFeeTaxable: boolean | null;
  sessionIdleTimeoutMinutes: number;
  guestJwtTtlMinutes: number;
  batchClaimTtlSeconds: number;
  maxPendingBatchesPerSession: number;
  joinRateLimitPerMinute: number;
  orderRateLimitPerMinute: number;
  maxItemQuantity: number;
  orderConfirmationMode: OrderConfirmationMode;
  requireTablePin: boolean;
  cleaningAutoRelease: boolean;
  /** Cả ba cùng null (xóa cấu hình) hoặc cùng có giá trị. PUT thay thế toàn bộ nên luôn gửi đủ. */
  bankBin: string | null;
  bankAccountNo: string | null;
  bankAccountName: string | null;
  version: number;
}

export function useUpdateSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: UpdateSettingsInput) => api.put<SettingsDto>("/settings", input),
    onSuccess: (data) => qc.setQueryData(SETTINGS_KEY, data),
  });
}
