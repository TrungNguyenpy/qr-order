"use client";

import { useState } from "react";
import { Badge, Banner } from "@/components/ui/Feedback";
import { Button } from "@/components/ui/Button";
import { Field, Input, Select } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { ReasonDialog } from "@/components/ui/ReasonDialog";
import { toast } from "@/stores/toast";
import { useAuthStore } from "@/stores/auth";
import { hasPermission } from "@/lib/access";
import { describeError } from "@/lib/errors";
import { formatDateTime, formatVnd } from "@/lib/format";
import type { BillDto, RefundDto } from "@/lib/api/types";
import {
  BILL_STATUS_LABEL,
  BILL_STATUS_TONE,
  ITEM_STATUS_LABEL,
  PAYMENT_METHODS,
  PAYMENT_METHOD_LABEL,
  PAYMENT_STATUS_LABEL,
  TRANSACTION_STATUS_LABEL,
  TRANSACTION_STATUS_TONE,
  VOID_TYPE_LABEL,
} from "@/features/orders/labels";
import {
  useConfirmRefund,
  useCreateReplacementBill,
  useRecordRefund,
  useRequestVoid,
  useWithdrawVoid,
} from "./api";
import { validateRefundAmount } from "./billingUtils";
import { FormError } from "@/components/ui/FormError";

type Dialog = "void" | "withdraw" | "refund" | null;

function Row({
  label,
  value,
  strong,
}: {
  label: string;
  value: React.ReactNode;
  strong?: boolean;
}) {
  return (
    <div className="flex justify-between gap-4 py-0.5">
      <dt className="text-ink-2">{label}</dt>
      <dd
        className={
          strong
            ? "font-semibold text-ink tabular-nums"
            : "text-ink tabular-nums"
        }
      >
        {value}
      </dd>
    </div>
  );
}

/** Hóa đơn hiện tại của đơn + các thao tác Void / Rút Void / Hoàn tiền (theo permission hiệu lực). */
export function BillPanel({ bill }: { bill: BillDto }) {
  const user = useAuthStore((s) => s.user);
  const canVoid = hasPermission(user, "BILL_VOID");
  const canRefund = hasPermission(user, "REFUND_RECORD");
  const [dialog, setDialog] = useState<Dialog>(null);
  const [confirming, setConfirming] = useState<RefundDto | null>(null);
  const requestVoid = useRequestVoid();
  const withdraw = useWithdrawVoid();
  const close = () => setDialog(null);

  const voidPending = bill.status === "VOID_PENDING";

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium text-ink">{bill.billNumber}</span>
        <Badge tone={BILL_STATUS_TONE[bill.status]}>
          {BILL_STATUS_LABEL[bill.status]}
        </Badge>
        <Badge
          tone={
            bill.paymentStatus === "PAID"
              ? "ok"
              : bill.paymentStatus === "PARTIALLY_PAID"
                ? "warn"
                : "neutral"
          }
        >
          {PAYMENT_STATUS_LABEL[bill.paymentStatus]}
        </Badge>
        {bill.replacesBillId && (
          <span className="text-xs text-ink-3">
            Thay cho hóa đơn #{bill.replacesBillId}
          </span>
        )}
      </div>

      {voidPending && (
        <Banner tone="warn" title="Hóa đơn đang chờ hoàn tiền để hủy">
          {bill.voidReason ? `Lý do: ${bill.voidReason}. ` : ""}Còn phải hoàn{" "}
          <b>{formatVnd(bill.remainingRefundable)}</b>
          {bill.refundPendingAmount > 0
            ? ` (đang chờ xác nhận ${formatVnd(bill.refundPendingAmount)})`
            : ""}
          .
        </Banner>
      )}
      {bill.status === "VOIDED" && bill.voidType && (
        <p className="text-[13px] text-ink-2">
          {VOID_TYPE_LABEL[bill.voidType] ?? bill.voidType}
          {bill.voidReason ? ` — ${bill.voidReason}` : ""} ·{" "}
          {formatDateTime(bill.voidedAt)}
        </p>
      )}

      <div className="overflow-x-auto rounded-card border border-line">
        <table className="w-full min-w-[420px] text-[13px]">
          <caption className="sr-only">Các dòng của hóa đơn</caption>
          <thead>
            <tr className="border-b border-line bg-surface text-left text-xs text-ink-3">
              <th scope="col" className="px-3 py-2 font-medium">
                Món
              </th>
              <th scope="col" className="px-3 py-2 text-right font-medium">
                SL
              </th>
              <th scope="col" className="px-3 py-2 text-right font-medium">
                Đơn giá
              </th>
              <th scope="col" className="px-3 py-2 text-right font-medium">
                Thành tiền
              </th>
            </tr>
          </thead>
          <tbody>
            {bill.lines.map((l) => (
              <tr
                key={l.itemId}
                className="border-b border-line last:border-b-0"
              >
                <td className="px-3 py-2">
                  <span className="text-ink">{l.productName}</span>
                  {l.options.length > 0 && (
                    <span className="block text-xs text-ink-3">
                      {l.options.join(", ")}
                    </span>
                  )}
                  {l.note && (
                    <span className="block text-xs text-ink-3">
                      Ghi chú: {l.note}
                    </span>
                  )}
                  {l.status !== "SERVED" && (
                    <span className="block text-xs text-ink-3">
                      {ITEM_STATUS_LABEL[l.status]}
                    </span>
                  )}
                </td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {l.quantity}
                </td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {formatVnd(l.unitPrice)}
                </td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {formatVnd(l.lineTotal)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <dl className="text-[13px]">
        <Row label="Tạm tính" value={formatVnd(bill.subtotalAmount)} />
        {bill.discountAmount > 0 && (
          <Row
            label={`Giảm giá${bill.discountReason ? ` (${bill.discountReason})` : ""}`}
            value={`−${formatVnd(bill.discountAmount)}`}
          />
        )}
        <Row label="Phí dịch vụ" value={formatVnd(bill.serviceFeeAmount)} />
        <Row label="VAT" value={formatVnd(bill.vatAmount)} />
        <Row label="Tổng cộng" value={formatVnd(bill.totalAmount)} strong />
        <Row label="Đã thanh toán" value={formatVnd(bill.paidAmount)} />
        {bill.pendingAmount > 0 && (
          <Row
            label="Đang chờ xác nhận"
            value={formatVnd(bill.pendingAmount)}
          />
        )}
        <Row label="Còn phải thu" value={formatVnd(bill.remainingAmount)} />
        {(bill.refundedAmount > 0 || voidPending) && (
          <Row label="Đã hoàn" value={formatVnd(bill.refundedAmount)} />
        )}
        {(voidPending || bill.remainingRefundable > 0) && (
          <Row
            label="Còn phải hoàn (remaining_refundable)"
            value={formatVnd(bill.remainingRefundable)}
            strong
          />
        )}
      </dl>

      {bill.payments.length > 0 && (
        <div>
          <h4 className="mb-1 text-[13px] font-medium text-ink-2">
            Thanh toán
          </h4>
          <ul className="space-y-1 text-[13px]">
            {bill.payments.map((p) => (
              <li
                key={p.id}
                className="flex flex-wrap items-center justify-between gap-2"
              >
                <span>
                  {PAYMENT_METHOD_LABEL[p.method]} ·{" "}
                  {formatDateTime(p.confirmedAt ?? p.createdAt)}
                  {p.receiptNumber ? ` · ${p.receiptNumber}` : ""}
                </span>
                <span className="flex items-center gap-2">
                  <b className="tabular-nums">{formatVnd(p.amount)}</b>
                  <Badge tone={TRANSACTION_STATUS_TONE[p.status]}>
                    {TRANSACTION_STATUS_LABEL[p.status]}
                  </Badge>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {bill.refunds.length > 0 && (
        <div>
          <h4 className="mb-1 text-[13px] font-medium text-ink-2">Hoàn tiền</h4>
          <ul className="space-y-1 text-[13px]">
            {bill.refunds.map((r) => (
              <li
                key={r.id}
                className="flex flex-wrap items-center justify-between gap-2"
              >
                <span>
                  {PAYMENT_METHOD_LABEL[r.method]} ·{" "}
                  {formatDateTime(r.confirmedAt ?? r.createdAt)} · {r.reason}
                </span>
                <span className="flex items-center gap-2">
                  <b className="tabular-nums">{formatVnd(r.amount)}</b>
                  <Badge tone={TRANSACTION_STATUS_TONE[r.status]}>
                    {TRANSACTION_STATUS_LABEL[r.status]}
                  </Badge>
                  {r.status === "PENDING" && canRefund && (
                    <Button size="sm" onClick={() => setConfirming(r)}>
                      Xác nhận
                    </Button>
                  )}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="flex flex-wrap gap-2 pt-1">
        {bill.status === "ACTIVE" && canVoid && (
          <Button variant="danger" size="sm" onClick={() => setDialog("void")}>
            Yêu cầu Void
          </Button>
        )}
        {voidPending && canVoid && (
          <Button size="sm" onClick={() => setDialog("withdraw")}>
            Rút Void
          </Button>
        )}
        {voidPending && canRefund && bill.remainingRefundable > 0 && (
          <Button
            variant="primary"
            size="sm"
            onClick={() => setDialog("refund")}
          >
            Ghi nhận hoàn tiền
          </Button>
        )}
      </div>
      {bill.status === "ACTIVE" && !canVoid && (
        <p className="text-xs text-ink-3">
          Cần quyền BILL_VOID để Void hóa đơn.
        </p>
      )}
      {voidPending && !canRefund && (
        <p className="text-xs text-ink-3">
          Cần quyền REFUND_RECORD để ghi nhận hoàn tiền.
        </p>
      )}

      {dialog === "void" && (
        <ReasonDialog
          open
          title={`Void hóa đơn ${bill.billNumber}`}
          description="Hóa đơn chuyển sang chờ hoàn tiền; sau khi hoàn đủ, hóa đơn sẽ bị hủy và đơn cần Bill thay thế."
          confirmLabel="Yêu cầu Void"
          tone="danger"
          reasonLabel="Lý do Void"
          reasonMax={1000}
          onSubmit={async ({ reason }) => {
            const r = await requestVoid.mutateAsync({
              billId: bill.id,
              reason,
              version: bill.version,
            });
            toast.success(
              r.voidCompleted
                ? "Hóa đơn đã được hủy."
                : "Đã yêu cầu Void. Hãy ghi nhận hoàn tiền.",
            );
          }}
          onClose={close}
          onReload={close}
        />
      )}
      {dialog === "withdraw" && (
        <ReasonDialog
          open
          title={`Rút yêu cầu Void ${bill.billNumber}`}
          description="Hóa đơn trở lại hiệu lực nếu chưa hoàn tiền."
          confirmLabel="Rút Void"
          reasonLabel="Lý do rút Void"
          reasonMax={1000}
          onSubmit={async ({ reason }) => {
            await withdraw.mutateAsync({ billId: bill.id, reason });
            toast.success("Đã rút yêu cầu Void.");
          }}
          onClose={close}
        />
      )}
      {dialog === "refund" && <RefundDialog bill={bill} onClose={close} />}
      {confirming && (
        <ConfirmRefundDialog
          refund={confirming}
          onClose={() => setConfirming(null)}
        />
      )}
    </div>
  );
}

function RefundDialog({
  bill,
  onClose,
}: {
  bill: BillDto;
  onClose: () => void;
}) {
  const record = useRecordRefund();
  // Sinh một lần khi mở hộp thoại: thử lại sau lỗi mạng dùng cùng requestId để backend không ghi trùng.
  const [requestId] = useState(() => crypto.randomUUID());
  const [method, setMethod] =
    useState<(typeof PAYMENT_METHODS)[number]>("CASH");
  const [amount, setAmount] = useState(String(bill.remainingRefundable));
  const [status, setStatus] = useState<"SUCCESS" | "PENDING">("SUCCESS");
  const [amountError, setAmountError] = useState<string | undefined>();

  return (
    <ReasonDialog
      open
      title={`Hoàn tiền cho ${bill.billNumber}`}
      description={`Tối đa ${formatVnd(bill.remainingRefundable)} (remaining_refundable).`}
      confirmLabel="Ghi nhận hoàn tiền"
      reasonLabel="Lý do hoàn tiền"
      reasonMax={1000}
      validate={() => {
        const err = validateRefundAmount(amount, bill.remainingRefundable);
        setAmountError(err ?? undefined);
        return err === null;
      }}
      onSubmit={async ({ reason }) => {
        const r = await record.mutateAsync({
          billId: bill.id,
          requestId,
          method,
          amount: Number(amount),
          status,
          reason,
        });
        toast.success(
          r.voidCompleted
            ? "Đã hoàn đủ, hóa đơn được hủy. Cần tạo Bill thay thế."
            : r.replayed
              ? "Giao dịch đã được ghi nhận trước đó."
              : "Đã ghi nhận hoàn tiền.",
        );
      }}
      onClose={onClose}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Số tiền (đ)" required error={amountError}>
          <Input
            inputMode="numeric"
            value={amount}
            onChange={(e) => {
              setAmount(e.target.value);
              setAmountError(undefined);
            }}
          />
        </Field>
        <Field label="Phương thức" required>
          <Select
            value={method}
            onChange={(e) => setMethod(e.target.value as typeof method)}
          >
            {PAYMENT_METHODS.map((m) => (
              <option key={m} value={m}>
                {PAYMENT_METHOD_LABEL[m]}
              </option>
            ))}
          </Select>
        </Field>
        <Field
          label="Trạng thái giao dịch"
          required
          hint="Chọn “Đang chờ” nếu tiền chưa thực sự trả cho khách (ví dụ chuyển khoản)."
          className="sm:col-span-2"
        >
          <Select
            value={status}
            onChange={(e) => setStatus(e.target.value as typeof status)}
          >
            <option value="SUCCESS">Đã hoàn tiền cho khách</option>
            <option value="PENDING">Đang chờ xác nhận</option>
          </Select>
        </Field>
      </div>
    </ReasonDialog>
  );
}

function ConfirmRefundDialog({
  refund,
  onClose,
}: {
  refund: RefundDto;
  onClose: () => void;
}) {
  const confirm = useConfirmRefund();
  const [failed, setFailed] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(result: "SUCCESS" | "FAILED") {
    if (result === "FAILED" && !reason.trim()) {
      setError("Nhập lý do thất bại.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await confirm.mutateAsync({
        refundId: refund.id,
        result,
        failureReason: result === "FAILED" ? reason.trim() : undefined,
      });
      toast.success(
        result === "SUCCESS"
          ? "Đã xác nhận hoàn tiền."
          : "Đã ghi nhận hoàn tiền thất bại.",
      );
      onClose();
    } catch (e) {
      setError(describeError(e).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      title={`Xác nhận hoàn tiền ${formatVnd(refund.amount)}`}
      onClose={onClose}
      dismissible={!busy}
      footer={
        failed ? (
          <>
            <Button onClick={() => setFailed(false)} disabled={busy}>
              Quay lại
            </Button>
            <Button
              variant="danger"
              loading={busy}
              onClick={() => void submit("FAILED")}
            >
              Ghi nhận thất bại
            </Button>
          </>
        ) : (
          <>
            <Button onClick={onClose} disabled={busy}>
              Đóng
            </Button>
            <Button onClick={() => setFailed(true)} disabled={busy}>
              Thất bại
            </Button>
            <Button
              variant="primary"
              loading={busy}
              onClick={() => void submit("SUCCESS")}
            >
              Đã hoàn xong
            </Button>
          </>
        )
      }
    >
      <FormError
        info={
          error
            ? {
                code: "X",
                message: error,
                fields: {},
                conflict: false,
                reauthFailed: false,
              }
            : null
        }
      />
      <p className="text-[13px] text-ink-2">
        Khoản hoàn {PAYMENT_METHOD_LABEL[refund.method]} đang chờ. Lý do hoàn:{" "}
        {refund.reason}
      </p>
      {failed && (
        <Field label="Lý do thất bại" required className="mt-3">
          <Input
            data-autofocus
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </Field>
      )}
    </Modal>
  );
}

/** Nút tạo Bill thay thế (BILL_VOID) cho đơn đang chờ Bill mới. */
export function ReplacementBillButton({
  orderId,
  billNumber,
}: {
  orderId: number;
  billNumber?: string | null;
}) {
  const user = useAuthStore((s) => s.user);
  const create = useCreateReplacementBill();
  const [open, setOpen] = useState(false);
  if (!hasPermission(user, "BILL_VOID"))
    return <span className="text-xs text-ink-3">Cần quyền BILL_VOID</span>;
  return (
    <>
      <Button variant="primary" size="sm" onClick={() => setOpen(true)}>
        Tạo Bill thay thế
      </Button>
      {open && (
        <ReasonDialog
          open
          title="Tạo Bill thay thế"
          description={`Phát hành hóa đơn mới thay cho ${billNumber ?? "hóa đơn đã hủy"}. Giảm giá (nếu có) cần cấp lại ở màn hình thu ngân.`}
          confirmLabel="Tạo Bill"
          reason="none"
          onSubmit={async () => {
            const bill = await create.mutateAsync({ orderId });
            toast.success(`Đã tạo hóa đơn ${bill.billNumber}.`);
          }}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
}
