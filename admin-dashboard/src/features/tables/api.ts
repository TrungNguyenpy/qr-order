import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, api, downloadBlob } from "@/lib/api/client";
import { saveBlob } from "@/lib/api/files";
import { cleanParams } from "@/lib/api/query";
import type { FloorPlanTable, Page, QrCodeDto, TableDto, TableStatus } from "@/lib/api/types";
import { POLL_INTERVAL_MS } from "@/lib/realtime/stomp";

export interface TableFilters {
  q: string;
  area: string;
  status: TableStatus | "";
  active: "" | "true" | "false";
  page: number;
  size: number;
}

export function useTables(f: TableFilters) {
  return useQuery({
    queryKey: ["tables", "list", f],
    queryFn: () => api.get<Page<TableDto>>("/tables", cleanParams(f)),
    placeholderData: keepPreviousData,
  });
}

/** `poll`: tải lại định kỳ khi không có realtime (ADMIN, hoặc STOMP mất kết nối). */
export function useFloorPlan(poll: boolean) {
  return useQuery({
    queryKey: ["floor-plan"],
    queryFn: () => api.get<FloorPlanTable[]>("/tables/floor-plan"),
    refetchInterval: poll ? POLL_INTERVAL_MS : false,
  });
}

function useTableMutation<TVars, TData>(fn: (v: TVars) => Promise<TData>, extraKeys: string[] = []) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      for (const k of ["tables", "floor-plan", ...extraKeys]) void qc.invalidateQueries({ queryKey: [k] });
    },
  });
}

export interface TableInput {
  code: string;
  displayName: string;
  area?: string;
  capacity: number;
}

export const useCreateTable = () => useTableMutation((v: TableInput & { displayOrder?: number }) => api.post<TableDto>("/tables", v));

export const useUpdateTable = () =>
  useTableMutation((v: TableInput & { id: number; displayOrder: number; version: number }) => {
    const { id, ...body } = v;
    return api.put<TableDto>(`/tables/${id}`, body);
  });

export const useSetTableActive = () =>
  useTableMutation((v: { id: number; active: boolean; version: number }) => api.patch<TableDto>(`/tables/${v.id}/active`, { active: v.active, version: v.version }));

/** Xác nhận dọn xong: CLEANING → AVAILABLE (MANAGER/STAFF). */
export const useConfirmCleaning = () =>
  useTableMutation((v: { id: number; version: number }) => api.patch<TableDto>(`/tables/${v.id}/status`, { status: "AVAILABLE", version: v.version }));

// ------------------------------------------------------------------------------------------------ QR

/** QR ACTIVE của bàn; bàn chưa có QR (409 QR_CODE_NOT_ACTIVE) trả `null` chứ không phải lỗi. */
export function useActiveQr(tableId: number | null) {
  return useQuery({
    queryKey: ["qr", tableId, "active"],
    enabled: tableId !== null,
    queryFn: async () => {
      try {
        return await api.get<QrCodeDto>(`/tables/${tableId}/qr-code`);
      } catch (e) {
        if (e instanceof ApiError && e.code === "QR_CODE_NOT_ACTIVE") return null;
        throw e;
      }
    },
  });
}

export function useQrHistory(tableId: number | null, enabled: boolean) {
  return useQuery({
    queryKey: ["qr", tableId, "history"],
    enabled: tableId !== null && enabled,
    queryFn: () => api.get<QrCodeDto[]>(`/tables/${tableId}/qr-codes`),
  });
}

function useQrMutation<TVars, TData>(tableId: number, fn: (v: TVars) => Promise<TData>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["qr", tableId] });
      void qc.invalidateQueries({ queryKey: ["floor-plan"] }); // hasActiveQr đổi
    },
  });
}

export const useGenerateQr = (tableId: number) => useQrMutation(tableId, () => api.post<QrCodeDto>(`/tables/${tableId}/qr-codes`));
export const useRotateQr = (tableId: number) => useQrMutation(tableId, (v: { reason?: string }) => api.post<QrCodeDto>(`/tables/${tableId}/qr-code/rotate`, { reason: v.reason || undefined }));
export const useRevokeQr = (tableId: number) => useQrMutation(tableId, (v: { reason: string }) => api.post<QrCodeDto>(`/tables/${tableId}/qr-code/revoke`, { reason: v.reason }));

/** Xem trước PNG (size 128–2048). Gọi lại khi QR đổi (key theo `qrId`). */
export function useQrPng(tableId: number, qrId: number | undefined, size = 512) {
  return useQuery({
    queryKey: ["qr", tableId, "png", qrId, size],
    enabled: qrId !== undefined,
    queryFn: () => downloadBlob(`/tables/${tableId}/qr-code/image.png`, { size }),
    staleTime: 0,
    gcTime: 0, // ảnh QR là dữ liệu nhạy cảm: không giữ lại trong cache
  });
}

export async function downloadQrPng(tableId: number, code: string) {
  const { blob, fileName } = await downloadBlob(`/tables/${tableId}/qr-code/image.png`, { size: 1024, download: true });
  saveBlob(blob, fileName ?? `qr-${code}.png`);
}

export async function downloadQrPdf(tableId: number, code: string) {
  const { blob, fileName } = await downloadBlob(`/tables/${tableId}/qr-code/print.pdf`);
  saveBlob(blob, fileName ?? `qr-${code}.pdf`);
}

/** PDF hàng loạt; `tableIds` rỗng = mọi bàn đang dùng có QR ACTIVE (backend giới hạn 200 bàn). */
export async function downloadBulkQrPdf(tableIds: number[]) {
  const { blob, fileName } = await downloadBlob("/qr-codes/print.pdf", tableIds.length > 0 ? { tableIds } : undefined);
  saveBlob(blob, fileName ?? "qr-codes.pdf");
}
