"use client";

import { useEffect, useMemo, useState } from "react";
import type { TableDto } from "@/lib/api/types";
import { describeError } from "@/lib/errors";
import { formatDateTime } from "@/lib/format";
import { toast } from "@/stores/toast";
import { Badge, Banner, EmptyState, ErrorState, Skeleton } from "@/components/ui/Feedback";
import { Button } from "@/components/ui/Button";
import { Modal } from "@/components/ui/Modal";
import { ReasonDialog } from "@/components/ui/ReasonDialog";
import { Tabs } from "@/components/ui/Tabs";
import { downloadQrPdf, downloadQrPng, useActiveQr, useGenerateQr, useQrHistory, useQrPng, useRevokeQr, useRotateQr } from "./api";
import { QR_STATUS_LABEL, QR_STATUS_TONE } from "./labels";

/** Ảnh QR: tải PNG kèm Authorization thành blob rồi hiển thị bằng object URL (thu hồi khi đổi/đóng). */
function QrImage({ tableId, qrId, name }: { tableId: number; qrId: number; name: string }) {
  const png = useQrPng(tableId, qrId);
  const blob = png.data?.blob;
  const url = useMemo(() => (blob ? URL.createObjectURL(blob) : null), [blob]);
  useEffect(() => {
    if (!url) return;
    return () => URL.revokeObjectURL(url);
  }, [url]);

  if (png.isPending) return <Skeleton className="h-56 w-56" />;
  if (png.isError) return <ErrorState compact error={png.error} onRetry={() => void png.refetch()} />;
  // eslint-disable-next-line @next/next/no-img-element -- blob URL, không dùng được next/image
  return url ? <img src={url} alt={`Mã QR ${name}`} className="h-56 w-56 rounded-card border border-line bg-white p-2" /> : null;
}

type QrTab = "current" | "history";
type Confirm = "rotate" | "revoke" | null;

export function QrModal({ table, onClose }: { table: TableDto; onClose: () => void }) {
  const [tab, setTab] = useState<QrTab>("current");
  const [confirm, setConfirm] = useState<Confirm>(null);
  const [downloading, setDownloading] = useState<"png" | "pdf" | null>(null);
  const active = useActiveQr(table.id);
  const history = useQrHistory(table.id, tab === "history");
  const generate = useGenerateQr(table.id);
  const rotate = useRotateQr(table.id);
  const revoke = useRevokeQr(table.id);
  const qr = active.data ?? null;

  async function onGenerate() {
    try {
      await generate.mutateAsync();
      toast.success("Đã sinh mã QR cho bàn.");
    } catch (e) {
      toast.error(describeError(e).message);
    }
  }

  async function onDownload(kind: "png" | "pdf") {
    setDownloading(kind);
    try {
      if (kind === "png") await downloadQrPng(table.id, table.code);
      else await downloadQrPdf(table.id, table.code);
    } catch (e) {
      toast.error(describeError(e).message);
    } finally {
      setDownloading(null);
    }
  }

  async function copyUrl(url: string) {
    try {
      await navigator.clipboard.writeText(url);
      toast.success("Đã sao chép liên kết.");
    } catch {
      toast.error("Không sao chép được. Hãy chọn và sao chép thủ công.");
    }
  }

  return (
    <Modal open size="lg" title={`Mã QR - ${table.displayName}`} description={`Mã bàn ${table.code}${table.area ? ` · ${table.area}` : ""}`} onClose={onClose}>
      <Tabs
        tabs={[
          { id: "current" as const, label: "Mã hiện tại" },
          { id: "history" as const, label: "Lịch sử" },
        ]}
        value={tab}
        onChange={setTab}
      />

      {tab === "current" && (
        <div>
          {active.isPending && <Skeleton className="h-56 w-full" />}
          {active.isError && <ErrorState compact error={active.error} onRetry={() => void active.refetch()} />}
          {active.isSuccess && qr === null && (
            <EmptyState
              title="Bàn chưa có mã QR đang dùng"
              description="Sinh mã QR để khách quét và gọi món tại bàn này."
              action={
                <Button variant="primary" loading={generate.isPending} onClick={() => void onGenerate()}>
                  Sinh mã QR
                </Button>
              }
            />
          )}
          {qr && (
            <div className="space-y-4">
              {qr.reproducible === false && (
                <Banner tone="warn" title="Không dựng lại được ảnh cho mã này">
                  Mã QR này là dữ liệu mẫu hoặc khóa bí mật đã đổi. Hãy xoay mã QR mới trước khi in và dán tại bàn.
                </Banner>
              )}
              <div className="flex flex-wrap items-start gap-5">
                {qr.reproducible === false ? (
                  <div className="flex h-56 w-56 items-center justify-center rounded-card border border-dashed border-line-strong text-center text-[13px] text-ink-3">Không có ảnh</div>
                ) : (
                  <QrImage tableId={table.id} qrId={qr.id} name={table.displayName} />
                )}
                <div className="min-w-0 flex-1 space-y-3">
                  <div className="flex items-center gap-2">
                    <Badge tone={QR_STATUS_TONE[qr.status]}>{QR_STATUS_LABEL[qr.status]}</Badge>
                    <span className="text-[13px] text-ink-3">Tạo lúc {formatDateTime(qr.createdAt)}</span>
                  </div>
                  {qr.url && (
                    <div>
                      <p className="text-[13px] font-medium text-ink-2">Liên kết</p>
                      <p className="break-all rounded-ctl border border-line bg-surface-2 px-2.5 py-1.5 font-mono text-xs">{qr.url}</p>
                      <Button size="sm" className="mt-1.5" onClick={() => void copyUrl(qr.url as string)}>
                        Sao chép liên kết
                      </Button>
                    </div>
                  )}
                  <div className="flex flex-wrap gap-2">
                    <Button loading={downloading === "png"} disabled={!!downloading || qr.reproducible === false} onClick={() => void onDownload("png")}>
                      Tải PNG
                    </Button>
                    <Button loading={downloading === "pdf"} disabled={!!downloading || qr.reproducible === false} onClick={() => void onDownload("pdf")}>
                      Tải PDF in (A5)
                    </Button>
                  </div>
                </div>
              </div>
              <div className="flex flex-wrap justify-end gap-2 border-t border-line pt-4">
                <Button onClick={() => setConfirm("rotate")}>Xoay mã mới</Button>
                <Button variant="danger" onClick={() => setConfirm("revoke")}>
                  Thu hồi mã
                </Button>
              </div>
            </div>
          )}
        </div>
      )}

      {tab === "history" && (
        <div>
          {history.isPending && <Skeleton className="h-32 w-full" />}
          {history.isError && <ErrorState compact error={history.error} onRetry={() => void history.refetch()} />}
          {history.data && history.data.length === 0 && <EmptyState title="Chưa có lịch sử mã QR" />}
          {history.data && history.data.length > 0 && (
            <ul className="divide-y divide-line rounded-card border border-line">
              {history.data.map((h) => (
                <li key={h.id} className="space-y-0.5 px-4 py-3">
                  <div className="flex items-center gap-2">
                    <Badge tone={QR_STATUS_TONE[h.status]}>{QR_STATUS_LABEL[h.status]}</Badge>
                    <span className="text-[13px] text-ink-2">Tạo {formatDateTime(h.createdAt)}</span>
                  </div>
                  {h.deactivatedAt && (
                    <p className="text-[13px] text-ink-3">
                      Ngừng lúc {formatDateTime(h.deactivatedAt)}
                      {h.deactivationReason ? ` · Lý do: ${h.deactivationReason}` : ""}
                    </p>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {confirm === "rotate" && (
        <ReasonDialog
          open
          title="Xoay mã QR mới"
          description="Mã cũ bị vô hiệu ngay: khách đang dùng mã cũ sẽ không quét được nữa. Nhớ in và thay mã mới tại bàn."
          confirmLabel="Xoay mã"
          reason="optional"
          reasonLabel="Lý do (tùy chọn)"
          onSubmit={async (v) => {
            await rotate.mutateAsync({ reason: v.reason.trim() });
            toast.success("Đã xoay mã QR mới.");
          }}
          onReload={() => setConfirm(null)}
          onClose={() => setConfirm(null)}
        />
      )}
      {confirm === "revoke" && (
        <ReasonDialog
          open
          title="Thu hồi mã QR"
          description="Mã bị vô hiệu và KHÔNG tạo mã mới; bàn sẽ không nhận được order cho đến khi sinh mã mới."
          confirmLabel="Thu hồi"
          tone="danger"
          reason="required"
          reasonLabel="Lý do thu hồi"
          onSubmit={async (v) => {
            await revoke.mutateAsync({ reason: v.reason.trim() });
            toast.success("Đã thu hồi mã QR.");
          }}
          onReload={() => setConfirm(null)}
          onClose={() => setConfirm(null)}
        />
      )}
    </Modal>
  );
}
