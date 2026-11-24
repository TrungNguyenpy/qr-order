import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, api } from "@/lib/api/client";
import { cleanParams } from "@/lib/api/query";
import type {
  BillDto,
  BillingAttentionItem,
  CancelRequestDto,
  Page,
  PaymentMethod,
  RefundDto,
  SessionStatus,
  StaffOrderDto,
  StaffSessionDto,
  TransactionStatus,
} from "@/lib/api/types";
import { POLL_INTERVAL_MS } from "@/lib/realtime/stomp";

/** Mọi query liên quan phiên/đơn/bill cần làm mới sau một thao tác (khớp khóa trong realtime/stomp.ts). */
const RELATED_KEYS = ["sessions", "session-order", "bill", "cancel-requests", "billing-attention", "tables", "floor-plan", "reports"];

function useInvalidateRelated() {
  const qc = useQueryClient();
  return () => {
    for (const k of RELATED_KEYS) void qc.invalidateQueries({ queryKey: [k] });
  };
}

// ------------------------------------------------------------------------------------------------ Phiên

export interface SessionFilters {
  /** Rỗng = mặc định của backend (OPEN + BILLING). */
  status: SessionStatus | "";
  needsAttention: boolean;
  page: number;
  size: number;
}

export function useSessions(f: SessionFilters, poll: boolean) {
  const params = cleanParams({ status: f.status, needsAttention: f.needsAttention ? true : undefined, page: f.page, size: f.size });
  return useQuery({
    queryKey: ["sessions", "list", params],
    queryFn: () => api.get<Page<StaffSessionDto>>("/sessions", params),
    placeholderData: keepPreviousData,
    refetchInterval: poll ? POLL_INTERVAL_MS : false,
  });
}

export function useSessionDetail(id: number | null) {
  return useQuery({
    queryKey: ["sessions", "detail", id],
    queryFn: () => api.get<StaffSessionDto>(`/sessions/${id}`),
    enabled: id !== null,
  });
}

/** Đơn của phiên (OrderAccess.FLOOR): chỉ MANAGER/STAFF. */
export function useSessionOrder(sessionId: number | null, enabled: boolean) {
  return useQuery({
    queryKey: ["session-order", sessionId],
    queryFn: () => api.get<StaffOrderDto>(`/orders/sessions/${sessionId}`),
    enabled: enabled && sessionId !== null,
  });
}

/** Bill mới nhất của đơn; 404 = đơn chưa có Bill (trả null, không phải lỗi). */
export function useLatestBill(orderId: number | null, enabled: boolean) {
  return useQuery({
    queryKey: ["bill", "latest", orderId],
    queryFn: async () => {
      try {
        return await api.get<BillDto>(`/orders/${orderId}/bill`);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    enabled: enabled && orderId !== null,
  });
}

export function useForceClose() {
  const inv = useInvalidateRelated();
  return useMutation({
    mutationFn: (v: { id: number; reason: string; version: number }) =>
      api.post<StaffSessionDto>(`/sessions/${v.id}/force-close`, { reason: v.reason, version: v.version }),
    onSuccess: inv,
  });
}

export function useReopenSession() {
  const inv = useInvalidateRelated();
  return useMutation({
    mutationFn: (v: { id: number; reason: string; version: number }) => api.post<unknown>(`/sessions/${v.id}/reopen`, { reason: v.reason, version: v.version }),
    onSuccess: inv,
  });
}

// ------------------------------------------------------------------------------------------------ Yêu cầu hủy món

export function useCancelRequests(enabled: boolean, poll: boolean) {
  return useQuery({
    queryKey: ["cancel-requests"],
    queryFn: () => api.get<CancelRequestDto[]>("/orders/cancel-requests"),
    enabled,
    refetchInterval: poll ? POLL_INTERVAL_MS : false,
  });
}

export function useApproveCancel() {
  const inv = useInvalidateRelated();
  return useMutation({
    mutationFn: (v: { id: number; note?: string }) => api.post<CancelRequestDto>(`/orders/cancel-requests/${v.id}/approve`, v.note ? { note: v.note } : {}),
    onSuccess: inv,
  });
}

/** Backend gọi là `deny` (không phải reject). */
export function useDenyCancel() {
  const inv = useInvalidateRelated();
  return useMutation({
    mutationFn: (v: { id: number; reason: string }) => api.post<CancelRequestDto>(`/orders/cancel-requests/${v.id}/deny`, { reason: v.reason }),
    onSuccess: inv,
  });
}

// ------------------------------------------------------------------------------------------------ Hóa đơn cần xử lý

export function useBillingAttention(enabled: boolean, poll: boolean) {
  return useQuery({
    queryKey: ["billing-attention"],
    queryFn: () => api.get<BillingAttentionItem[]>("/billing/attention"),
    enabled,
    refetchInterval: poll ? POLL_INTERVAL_MS : false,
  });
}

export function useRequestVoid() {
  const inv = useInvalidateRelated();
  return useMutation({
    mutationFn: (v: { billId: number; reason: string; version: number }) =>
      api.post<{ bill: BillDto; voidCompleted: boolean }>(`/bills/${v.billId}/void`, { reason: v.reason, version: v.version }),
    onSuccess: inv,
  });
}

export function useWithdrawVoid() {
  const inv = useInvalidateRelated();
  return useMutation({
    mutationFn: (v: { billId: number; reason: string }) => api.post<{ bill: BillDto; voidCompleted: boolean }>(`/bills/${v.billId}/void/withdraw`, { reason: v.reason }),
    onSuccess: inv,
  });
}

export function useCreateReplacementBill() {
  const inv = useInvalidateRelated();
  return useMutation({
    mutationFn: (v: { orderId: number }) => api.post<BillDto>(`/orders/${v.orderId}/replacement-bill`, {}),
    onSuccess: inv,
  });
}

export interface RefundInput {
  billId: number;
  /** UUID sinh một lần khi mở hộp thoại: gửi lại cùng giá trị khi thử lại để backend không ghi trùng (idempotent). */
  requestId: string;
  method: PaymentMethod;
  amount: number;
  status: Extract<TransactionStatus, "PENDING" | "SUCCESS">;
  reason: string;
}

export function useRecordRefund() {
  const inv = useInvalidateRelated();
  return useMutation({
    mutationFn: ({ billId, ...body }: RefundInput) =>
      api.post<{ refund: RefundDto; voidCompleted: boolean; replayed: boolean }>(`/bills/${billId}/refunds`, body),
    onSuccess: inv,
  });
}

export function useConfirmRefund() {
  const inv = useInvalidateRelated();
  return useMutation({
    mutationFn: (v: { refundId: number; result: "SUCCESS" | "FAILED"; failureReason?: string }) =>
      api.post<{ refund: RefundDto; voidCompleted: boolean }>(`/refunds/${v.refundId}/confirm`, { result: v.result, failureReason: v.failureReason }),
    onSuccess: inv,
  });
}
