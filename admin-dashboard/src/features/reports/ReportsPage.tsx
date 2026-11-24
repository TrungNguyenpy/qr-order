"use client";

import { useMemo, useState } from "react";
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Button } from "@/components/ui/Button";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { Field, Input, Select } from "@/components/ui/Field";
import { Banner, EmptyState, ErrorState, Skeleton } from "@/components/ui/Feedback";
import { NeedPermission } from "@/components/ui/NeedPermission";
import { PageHeader, Toolbar } from "@/components/ui/PageHeader";
import { toast } from "@/stores/toast";
import { useAuthStore } from "@/stores/auth";
import { hasPermission } from "@/lib/access";
import { friendlyError } from "@/lib/errors";
import { formatNumber, formatVnd } from "@/lib/format";
import type { ReportGroupBy, TopProduct, TopProductSort } from "@/lib/api/types";
import {
  downloadRevenueCsv,
  downloadTopProductsCsv,
  useRevenue,
  useServiceTime,
  useSummary,
  useTopProducts,
  type ReportFilters,
} from "./api";
import { GROUP_LABEL, METHOD_LABEL, REASON_LABEL, SEGMENT_LABEL, compactVnd, defaultRange, formatDuration, formatRatePercent, toChartData, validateRange } from "./reportUtils";

function Kpi({ label, value, hint, tone }: { label: string; value: string; hint?: string; tone?: "warn" | "danger" }) {
  return (
    <div className="rounded-card border border-line bg-bg px-4 py-3">
      <p className="text-xs font-medium uppercase tracking-wide text-ink-3">{label}</p>
      <p className={`mt-1 text-xl font-semibold tabular-nums ${tone === "danger" ? "text-danger" : tone === "warn" ? "text-warn" : "text-ink"}`}>{value}</p>
      {hint && <p className="mt-0.5 text-xs text-ink-3">{hint}</p>}
    </div>
  );
}

function KpiSkeleton({ n = 8 }: { n?: number }) {
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4" role="status" aria-label="Đang tải dữ liệu">
      {Array.from({ length: n }, (_, i) => (
        <Skeleton key={i} className="h-20" />
      ))}
    </div>
  );
}

function Section({ title, actions, children }: { title: string; actions?: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="mt-6">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-ink">{title}</h2>
        {actions}
      </div>
      {children}
    </section>
  );
}

export function ReportsPage() {
  const user = useAuthStore((s) => s.user);
  const allowed = hasPermission(user, "REPORT_VIEW");
  const init = useMemo(() => defaultRange(), []);
  const [draft, setDraft] = useState({ from: init.from, to: init.to });
  const [filters, setFilters] = useState<ReportFilters>({ ...init, groupBy: "DAY", shift: "", sortBy: "QUANTITY", limit: 10 });
  const [rangeError, setRangeError] = useState<string | null>(null);
  const [exporting, setExporting] = useState<"revenue" | "top" | null>(null);

  const summary = useSummary(filters, allowed);
  const revenue = useRevenue(filters, allowed);
  const top = useTopProducts(filters, allowed);
  const service = useServiceTime(filters, allowed);

  function apply() {
    const err = validateRange(draft.from, draft.to);
    setRangeError(err);
    if (!err) setFilters((f) => ({ ...f, from: draft.from, to: draft.to }));
  }

  async function exportCsv(kind: "revenue" | "top") {
    setExporting(kind);
    try {
      await (kind === "revenue" ? downloadRevenueCsv(filters) : downloadTopProductsCsv(filters));
      toast.success("Đã tải file CSV.");
    } catch (e) {
      toast.error(friendlyError(e));
    } finally {
      setExporting(null);
    }
  }

  const chartData = useMemo(() => (revenue.data ? toChartData(revenue.data.periods, filters.groupBy) : []), [revenue.data, filters.groupBy]);

  return (
    <div>
      <PageHeader title="Tổng quan & Báo cáo" description="Doanh thu, món bán chạy và hiệu suất phục vụ theo khoảng ngày (giờ Việt Nam)." />

      {!allowed ? (
        <NeedPermission permission="REPORT_VIEW" isAdmin={user?.role === "ADMIN"}>
          Báo cáo doanh thu cần quyền REPORT_VIEW.
        </NeedPermission>
      ) : (
        <>
          <Toolbar>
            <Field label="Từ ngày">
              <Input type="date" value={draft.from} max={draft.to || undefined} onChange={(e) => setDraft((d) => ({ ...d, from: e.target.value }))} />
            </Field>
            <Field label="Đến ngày (gồm cả ngày này)">
              <Input type="date" value={draft.to} min={draft.from || undefined} onChange={(e) => setDraft((d) => ({ ...d, to: e.target.value }))} />
            </Field>
            <Field label="Nhóm theo">
              <Select value={filters.groupBy} onChange={(e) => setFilters((f) => ({ ...f, groupBy: e.target.value as ReportGroupBy }))}>
                {(Object.keys(GROUP_LABEL) as ReportGroupBy[]).map((g) => (
                  <option key={g} value={g}>
                    {GROUP_LABEL[g]}
                  </option>
                ))}
              </Select>
            </Field>
            {filters.groupBy === "SHIFT" && (
              <Field label="Ca (tùy chọn)">
                <Input value={filters.shift} placeholder="Mã ca" onChange={(e) => setFilters((f) => ({ ...f, shift: e.target.value }))} />
              </Field>
            )}
            <Button variant="primary" onClick={apply}>
              Xem báo cáo
            </Button>
          </Toolbar>
          {rangeError && (
            <div className="mb-3">
              <Banner tone="danger">{rangeError}</Banner>
            </div>
          )}

          {/* KPI */}
          {summary.isPending ? (
            <KpiSkeleton />
          ) : summary.isError && !summary.data ? (
            <ErrorState error={summary.error} onRetry={() => void summary.refetch()} compact />
          ) : summary.data ? (
            <>
              <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
                <Kpi label="Doanh thu ròng" value={formatVnd(revenue.data?.totals.netRevenue)} hint={revenue.data ? `Gộp ${formatVnd(revenue.data.totals.grossRevenue)} − hoàn ${formatVnd(revenue.data.totals.refundedAmount)}` : undefined} />
                <Kpi label="Phiên đã mở" value={formatNumber(summary.data.sessionsOpened)} />
                <Kpi label="Đợt order" value={formatNumber(summary.data.batchesSubmitted)} hint={`Do nhân viên tạo: ${formatNumber(summary.data.batchesByStaff)}`} />
                <Kpi label="Tỷ lệ hủy / từ chối" value={`${formatRatePercent(summary.data.items.cancelRate)} / ${formatRatePercent(summary.data.items.rejectRate)}`} hint={`${formatNumber(summary.data.items.totalItems)} món`} />
                <Kpi label="Món đã làm bị hủy (lãng phí)" value={formatNumber(summary.data.items.wastedItems)} hint={formatVnd(summary.data.items.wastedValue)} tone={summary.data.items.wastedItems > 0 ? "warn" : undefined} />
                <Kpi label="Thất thu (xóa nợ)" value={formatVnd(summary.data.writtenOffShortfall)} hint={`${formatNumber(summary.data.writtenOffCount)} hóa đơn`} tone={summary.data.writtenOffCount > 0 ? "danger" : undefined} />
                <Kpi label="Hoàn tiền" value={formatVnd(summary.data.refundedAmount)} hint={`${formatNumber(summary.data.refundCount)} giao dịch`} />
                <Kpi label="Cần xử lý" value={formatNumber(summary.data.ordersNeedingAttention)} hint={`Bill chờ hủy: ${formatNumber(summary.data.billsVoidPending)}`} tone={summary.data.ordersNeedingAttention > 0 ? "warn" : undefined} />
              </div>
              {summary.data.items.byReason.length > 0 && (
                <p className="mt-2 text-xs text-ink-3">
                  Lý do hủy/từ chối:{" "}
                  {summary.data.items.byReason.map((r) => `${REASON_LABEL[r.reasonCode] ?? r.reasonCode} (${r.count})`).join(" · ")}
                </p>
              )}
            </>
          ) : null}

          {/* Doanh thu */}
          <Section
            title="Doanh thu"
            actions={
              <Button size="sm" loading={exporting === "revenue"} onClick={() => void exportCsv("revenue")}>
                Xuất CSV doanh thu
              </Button>
            }
          >
            {revenue.isPending ? (
              <Skeleton className="h-72" />
            ) : revenue.isError && !revenue.data ? (
              <ErrorState error={revenue.error} onRetry={() => void revenue.refetch()} compact />
            ) : revenue.data && revenue.data.periods.length === 0 ? (
              <EmptyState title="Chưa có doanh thu trong khoảng này" description="Thử chọn khoảng ngày khác." />
            ) : revenue.data ? (
              <div className="space-y-4">
                <div className="rounded-card border border-line p-3" style={{ height: 300 }}>
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={chartData} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" vertical={false} />
                      <XAxis dataKey="label" tick={{ fontSize: 12 }} />
                      <YAxis tickFormatter={compactVnd} tick={{ fontSize: 12 }} width={56} />
                      <Tooltip formatter={(v) => formatVnd(Number(v))} />
                      <Legend />
                      <Bar dataKey="net" name="Doanh thu ròng" fill="#2f4fe0" radius={[3, 3, 0, 0]} />
                      <Bar dataKey="refunded" name="Hoàn tiền" fill="#d97706" radius={[3, 3, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
                <div>
                  <h3 className="mb-1 text-[13px] font-medium text-ink-2">Theo phương thức thanh toán</h3>
                  <DataTable
                    caption="Doanh thu theo phương thức thanh toán"
                    rows={revenue.data.totals.byMethod}
                    rowKey={(m) => m.method}
                    columns={[
                      { key: "m", header: "Phương thức", cell: (m) => METHOD_LABEL[m.method] ?? m.method },
                      { key: "pc", header: "Lượt thu", align: "right", cell: (m) => formatNumber(m.paymentCount) },
                      { key: "pa", header: "Đã thu", align: "right", cell: (m) => formatVnd(m.paymentAmount) },
                      { key: "ra", header: "Hoàn", align: "right", cell: (m) => formatVnd(m.refundAmount) },
                      { key: "n", header: "Ròng", align: "right", cell: (m) => <b>{formatVnd(m.netAmount)}</b> },
                    ]}
                  />
                </div>
              </div>
            ) : null}
          </Section>

          {/* Top món */}
          <Section
            title="Món bán chạy"
            actions={
              <div className="flex flex-wrap items-center gap-2">
                <Select aria-label="Sắp xếp" className="!h-8 w-auto" value={filters.sortBy} onChange={(e) => setFilters((f) => ({ ...f, sortBy: e.target.value as TopProductSort }))}>
                  <option value="QUANTITY">Theo số lượng</option>
                  <option value="REVENUE">Theo doanh số</option>
                </Select>
                <Select aria-label="Số dòng" className="!h-8 w-auto" value={filters.limit} onChange={(e) => setFilters((f) => ({ ...f, limit: Number(e.target.value) }))}>
                  {[5, 10, 20, 50].map((n) => (
                    <option key={n} value={n}>
                      Top {n}
                    </option>
                  ))}
                </Select>
                <Button size="sm" loading={exporting === "top"} onClick={() => void exportCsv("top")}>
                  Xuất CSV món bán chạy
                </Button>
              </div>
            }
          >
            {top.isPending ? (
              <Skeleton className="h-40" />
            ) : top.isError && !top.data ? (
              <ErrorState error={top.error} onRetry={() => void top.refetch()} compact />
            ) : top.data && top.data.products.length === 0 ? (
              <EmptyState title="Chưa có món nào được bán" />
            ) : top.data ? (
              <DataTable<TopProduct>
                caption="Món bán chạy"
                rows={top.data.products}
                rowKey={(p) => p.productId}
                columns={
                  [
                    { key: "r", header: "#", cell: (p) => p.rank, className: "w-10" },
                    { key: "n", header: "Món", cell: (p) => <span className="font-medium">{p.productName}</span> },
                    { key: "c", header: "Danh mục", cell: (p) => p.categoryName },
                    { key: "q", header: "Số lượng", align: "right", cell: (p) => formatNumber(p.quantity) },
                    { key: "s", header: "Doanh số", align: "right", cell: (p) => formatVnd(p.sales) },
                    { key: "o", header: "Số đơn", align: "right", cell: (p) => formatNumber(p.orderCount) },
                  ] satisfies Column<TopProduct>[]
                }
              />
            ) : null}
          </Section>

          {/* Thời gian phục vụ */}
          <Section title="Thời gian phục vụ">
            {service.isPending ? (
              <Skeleton className="h-32" />
            ) : service.isError && !service.data ? (
              <ErrorState error={service.error} onRetry={() => void service.refetch()} compact />
            ) : service.data && service.data.servedItems === 0 ? (
              <EmptyState title="Chưa có món nào được phục vụ trong khoảng này" />
            ) : service.data ? (
              <>
                <DataTable
                  caption="Thời gian phục vụ theo công đoạn"
                  rows={service.data.segments}
                  rowKey={(s) => s.segment}
                  columns={[
                    { key: "s", header: "Công đoạn", cell: (s) => SEGMENT_LABEL[s.segment] ?? s.segment },
                    { key: "c", header: "Số mẫu", align: "right", cell: (s) => formatNumber(s.sampleCount) },
                    { key: "a", header: "Trung bình", align: "right", cell: (s) => formatDuration(s.averageSeconds) },
                    { key: "m", header: "Trung vị", align: "right", cell: (s) => formatDuration(s.medianSeconds) },
                  ]}
                />
                <p className="mt-1 text-xs text-ink-3">Dựa trên {formatNumber(service.data.servedItems)} món đã phục vụ.</p>
              </>
            ) : null}
          </Section>
        </>
      )}
    </div>
  );
}
