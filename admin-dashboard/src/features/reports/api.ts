import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { api, downloadBlob } from "@/lib/api/client";
import { saveBlob } from "@/lib/api/files";
import { cleanParams } from "@/lib/api/query";
import type { ReportGroupBy, RevenueReport, ServiceTimeReport, SummaryReport, TopProductSort, TopProductsReport } from "@/lib/api/types";

export interface ReportFilters {
  from: string;
  to: string;
  groupBy: ReportGroupBy;
  shift: string;
  sortBy: TopProductSort;
  limit: number;
}

/** `enabled` = người dùng có REPORT_VIEW (tránh gọi API chắc chắn 403). */
export function useRevenue(f: ReportFilters, enabled: boolean) {
  const params = cleanParams({ from: f.from, to: f.to, groupBy: f.groupBy, shift: f.groupBy === "SHIFT" ? f.shift.trim() : "" });
  return useQuery({
    queryKey: ["reports", "revenue", params],
    queryFn: () => api.get<RevenueReport>("/reports/revenue", params),
    enabled,
    placeholderData: keepPreviousData,
  });
}

export function useSummary(f: ReportFilters, enabled: boolean) {
  const params = cleanParams({ from: f.from, to: f.to });
  return useQuery({
    queryKey: ["reports", "summary", params],
    queryFn: () => api.get<SummaryReport>("/reports/summary", params),
    enabled,
    placeholderData: keepPreviousData,
  });
}

export function useTopProducts(f: ReportFilters, enabled: boolean) {
  const params = cleanParams({ from: f.from, to: f.to, limit: f.limit, sortBy: f.sortBy });
  return useQuery({
    queryKey: ["reports", "top-products", params],
    queryFn: () => api.get<TopProductsReport>("/reports/top-products", params),
    enabled,
    placeholderData: keepPreviousData,
  });
}

export function useServiceTime(f: ReportFilters, enabled: boolean) {
  const params = cleanParams({ from: f.from, to: f.to });
  return useQuery({
    queryKey: ["reports", "service-time", params],
    queryFn: () => api.get<ServiceTimeReport>("/reports/service-time", params),
    enabled,
    placeholderData: keepPreviousData,
  });
}

/** Tải CSV (UTF-8 có BOM) kèm Authorization; backend ghi audit REPORT_EXPORTED. */
export async function downloadRevenueCsv(f: ReportFilters) {
  const params = cleanParams({ from: f.from, to: f.to, groupBy: f.groupBy, shift: f.groupBy === "SHIFT" ? f.shift.trim() : "" });
  const { blob, fileName } = await downloadBlob("/reports/revenue.csv", params);
  saveBlob(blob, fileName ?? `revenue-${f.from}_${f.to}.csv`);
}

export async function downloadTopProductsCsv(f: ReportFilters) {
  const params = cleanParams({ from: f.from, to: f.to, limit: f.limit, sortBy: f.sortBy });
  const { blob, fileName } = await downloadBlob("/reports/top-products.csv", params);
  saveBlob(blob, fileName ?? `top-products-${f.from}_${f.to}.csv`);
}
