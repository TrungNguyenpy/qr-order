"use client";

import { useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import type { OrderConfirmationMode, SettingsDto } from "@/lib/api/types";
import { describeError, type ErrorInfo } from "@/lib/errors";
import { formatDateTime, percentToRate, rateToPercent } from "@/lib/format";
import { toast } from "@/stores/toast";
import { Button } from "@/components/ui/Button";
import { Banner, QueryBoundary, Skeleton } from "@/components/ui/Feedback";
import { Field, Input, Select, Switch } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { PageHeader } from "@/components/ui/PageHeader";
import { SETTINGS_KEY, useSettings, useUpdateSettings, type UpdateSettingsInput } from "./api";

const CONFIRMATION_MODES: Array<{ value: OrderConfirmationMode; label: string; hint: string }> = [
  { value: "FIRST_BATCH_ONLY", label: "Chỉ đợt đầu tiên cần nhân viên xác nhận", hint: "Sau khi phiên được xác minh, các đợt sau tự xác nhận (mặc định)." },
  { value: "ALL", label: "Mọi đợt đều cần nhân viên xác nhận", hint: "Tương đương EVERY_BATCH." },
  { value: "NONE", label: "Tự động xác nhận mọi đợt", hint: "Tương đương AUTO_CONFIRM: món đi thẳng vào bếp." },
];

/** Dạng chuỗi của form (giữ nguyên người dùng gõ, chuyển sang số khi kiểm tra). */
interface FormValues {
  vatPercent: string;
  servicePercent: string;
  serviceFeeTaxable: "" | "true" | "false";
  sessionIdleTimeoutMinutes: string;
  guestJwtTtlMinutes: string;
  batchClaimTtlSeconds: string;
  maxPendingBatchesPerSession: string;
  joinRateLimitPerMinute: string;
  orderRateLimitPerMinute: string;
  maxItemQuantity: string;
  orderConfirmationMode: OrderConfirmationMode;
  requireTablePin: boolean;
  cleaningAutoRelease: boolean;
}
type Errors = Partial<Record<keyof FormValues, string>>;

const INT_FIELDS = [
  "sessionIdleTimeoutMinutes",
  "guestJwtTtlMinutes",
  "batchClaimTtlSeconds",
  "maxPendingBatchesPerSession",
  "joinRateLimitPerMinute",
  "orderRateLimitPerMinute",
  "maxItemQuantity",
] as const;

function toForm(s: SettingsDto): FormValues {
  return {
    vatPercent: s.vatRate === null ? "" : String(rateToPercent(s.vatRate)),
    servicePercent: String(rateToPercent(s.serviceFeeRate)),
    serviceFeeTaxable: s.serviceFeeTaxable === null ? "" : s.serviceFeeTaxable ? "true" : "false",
    sessionIdleTimeoutMinutes: String(s.sessionIdleTimeoutMinutes),
    guestJwtTtlMinutes: String(s.guestJwtTtlMinutes),
    batchClaimTtlSeconds: String(s.batchClaimTtlSeconds),
    maxPendingBatchesPerSession: String(s.maxPendingBatchesPerSession),
    joinRateLimitPerMinute: String(s.joinRateLimitPerMinute),
    orderRateLimitPerMinute: String(s.orderRateLimitPerMinute),
    maxItemQuantity: String(s.maxItemQuantity),
    orderConfirmationMode: s.orderConfirmationMode,
    requireTablePin: s.requireTablePin,
    cleaningAutoRelease: s.cleaningAutoRelease,
  };
}

/** Tỷ lệ phần trăm: 0 <= x < 100, tối đa 2 chữ số thập phân (backend: 0 <= rate < 1, 4 chữ số thập phân). */
function parsePercent(raw: string): { value: number | null; error?: string } {
  const t = raw.trim().replace(",", ".");
  if (t === "") return { value: null };
  if (!/^\d+(\.\d{1,2})?$/.test(t)) return { value: null, error: "Nhập số từ 0 đến dưới 100, tối đa 2 chữ số thập phân." };
  const n = Number(t);
  if (n >= 100) return { value: null, error: "Phải nhỏ hơn 100%." };
  return { value: n };
}

/** Kiểm tra form; trả về lỗi theo trường hoặc dữ liệu đã chuyển đổi để gửi. */
export function validateSettings(v: FormValues, version: number): { errors: Errors; input?: UpdateSettingsInput } {
  const errors: Errors = {};

  const vat = parsePercent(v.vatPercent);
  if (vat.error) errors.vatPercent = vat.error;

  const svc = parsePercent(v.servicePercent);
  if (svc.error) errors.servicePercent = svc.error;
  else if (svc.value === null) errors.servicePercent = "Bắt buộc (nhập 0 nếu không thu phí phục vụ).";

  // BR-P04: phí phục vụ > 0 phải chốt có/không chịu VAT.
  if (svc.value !== null && svc.value > 0 && v.serviceFeeTaxable === "") {
    errors.serviceFeeTaxable = "Cần chọn khi phí phục vụ lớn hơn 0 (BR-P04).";
  }

  const ints: Partial<Record<(typeof INT_FIELDS)[number], number>> = {};
  for (const f of INT_FIELDS) {
    const t = v[f].trim();
    if (!/^\d+$/.test(t) || Number(t) <= 0 || Number(t) > 2_000_000_000) errors[f] = "Nhập số nguyên lớn hơn 0.";
    else ints[f] = Number(t);
  }

  if (Object.keys(errors).length > 0) return { errors };
  return {
    errors,
    input: {
      vatRate: vat.value === null ? null : percentToRate(vat.value),
      serviceFeeRate: percentToRate(svc.value ?? 0),
      serviceFeeTaxable: v.serviceFeeTaxable === "" ? null : v.serviceFeeTaxable === "true",
      sessionIdleTimeoutMinutes: ints.sessionIdleTimeoutMinutes!,
      guestJwtTtlMinutes: ints.guestJwtTtlMinutes!,
      batchClaimTtlSeconds: ints.batchClaimTtlSeconds!,
      maxPendingBatchesPerSession: ints.maxPendingBatchesPerSession!,
      joinRateLimitPerMinute: ints.joinRateLimitPerMinute!,
      orderRateLimitPerMinute: ints.orderRateLimitPerMinute!,
      maxItemQuantity: ints.maxItemQuantity!,
      orderConfirmationMode: v.orderConfirmationMode,
      requireTablePin: v.requireTablePin,
      cleaningAutoRelease: v.cleaningAutoRelease,
      version,
    },
  };
}

function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <section className="rounded-card border border-line">
      <header className="border-b border-line bg-surface px-5 py-3">
        <h2 className="font-semibold">{title}</h2>
        {description && <p className="text-[13px] text-ink-2">{description}</p>}
      </header>
      <div className="grid gap-5 p-5 sm:grid-cols-2">{children}</div>
    </section>
  );
}

function ToggleRow({ label, description, checked, onChange }: { label: string; description: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div className="flex items-start justify-between gap-4 sm:col-span-2">
      <div>
        <p className="font-medium">{label}</p>
        <p className="text-[13px] text-ink-2">{description}</p>
      </div>
      <Switch checked={checked} onChange={onChange} label={label} />
    </div>
  );
}

function SettingsForm({ data }: { data: SettingsDto }) {
  const update = useUpdateSettings();
  const qc = useQueryClient();
  const initial = toForm(data);
  const [values, setValues] = useState<FormValues>(initial);
  const [errors, setErrors] = useState<Errors>({});
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);

  const dirty = JSON.stringify(values) !== JSON.stringify(initial);
  function set<K extends keyof FormValues>(k: K, v: FormValues[K]) {
    setValues((p) => ({ ...p, [k]: v }));
    setErrors((p) => (p[k] ? { ...p, [k]: undefined } : p));
  }

  async function submit(e?: React.FormEvent) {
    e?.preventDefault();
    const { errors: errs, input } = validateSettings(values, data.version);
    setErrors(errs);
    if (!input) return;
    setApiError(null);
    try {
      await update.mutateAsync(input);
      toast.success("Đã lưu cấu hình nhà hàng.");
    } catch (err) {
      const info = describeError(err);
      const server: Errors = {};
      const map: Record<string, keyof FormValues> = { vatRate: "vatPercent", serviceFeeRate: "servicePercent" };
      for (const [f, m] of Object.entries(info.fields)) {
        const key = (map[f] ?? f) as keyof FormValues;
        if (key in values) server[key] = m;
      }
      setErrors(server);
      setApiError(info);
    }
  }

  const unit = (u: string) => <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-ink-3">{u}</span>;
  const intField = (key: (typeof INT_FIELDS)[number], label: string, unitLabel: string, hint?: string) => (
    <Field label={label} error={errors[key]} hint={hint} required>
      <div className="relative">
        <Input inputMode="numeric" value={values[key]} onChange={(e) => set(key, e.target.value)} className="pr-16" />
        {unit(unitLabel)}
      </div>
    </Field>
  );

  return (
    <form onSubmit={(e) => void submit(e)} noValidate className="space-y-6">
      <FormError
        info={apiError}
        hasFieldErrors={Object.values(errors).some(Boolean)}
        onReload={() => void qc.invalidateQueries({ queryKey: SETTINGS_KEY })}
      />
      {!data.vatConfigured && (
        <Banner tone="warn" title="Chưa cấu hình VAT">
          Khi VAT chưa được cấu hình, hệ thống chặn tạo hóa đơn (BR-P03). Nhập tỷ lệ VAT rồi lưu.
        </Banner>
      )}

      <Section title="Thuế và phí" description="Giá menu đã gồm VAT; VAT chỉ được trích ngược để hiển thị (DEC-10).">
        <Field label="Thuế VAT" error={errors.vatPercent} hint="Để trống = chưa cấu hình. Ví dụ 8 hoặc 10.">
          <div className="relative">
            <Input inputMode="decimal" value={values.vatPercent} onChange={(e) => set("vatPercent", e.target.value)} className="pr-10" />
            {unit("%")}
          </div>
        </Field>
        <Field label="Phí phục vụ" required error={errors.servicePercent} hint="Nhập 0 nếu không thu.">
          <div className="relative">
            <Input inputMode="decimal" value={values.servicePercent} onChange={(e) => set("servicePercent", e.target.value)} className="pr-10" />
            {unit("%")}
          </div>
        </Field>
        <Field label="Phí phục vụ có chịu VAT" error={errors.serviceFeeTaxable} hint="Bắt buộc chọn khi phí phục vụ > 0. Chờ kế toán xác nhận (P-5).">
          <Select value={values.serviceFeeTaxable} onChange={(e) => set("serviceFeeTaxable", e.target.value as FormValues["serviceFeeTaxable"])}>
            <option value="">Chưa chốt</option>
            <option value="true">Có chịu VAT</option>
            <option value="false">Không chịu VAT</option>
          </Select>
        </Field>
      </Section>

      <Section title="Phiên bàn và thời gian">
        {intField("sessionIdleTimeoutMinutes", "Phiên không hoạt động quá", "phút", "Quá hạn: phiên hết hạn hoặc gắn cờ cần xử lý.")}
        {intField("guestJwtTtlMinutes", "Thời hạn token của khách", "phút", "Khách được gia hạn tự động khi phiên còn hoạt động.")}
        {intField("batchClaimTtlSeconds", "Thời gian giữ xử lý một đợt", "giây", "Nhân viên nhận xử lý đợt sẽ giữ quyền trong khoảng này.")}
        <ToggleRow
          label="Tự động trả bàn sau khi dọn"
          description="Bật: sau khi đóng phiên bàn về Trống ngay; tắt: bàn chuyển Đang dọn và cần nhân viên xác nhận dọn xong."
          checked={values.cleaningAutoRelease}
          onChange={(v) => set("cleaningAutoRelease", v)}
        />
      </Section>

      <Section title="Đặt món và giới hạn">
        <div className="sm:col-span-2">
          <Field label="Chế độ xác nhận đợt gọi món" required>
            <Select value={values.orderConfirmationMode} onChange={(e) => set("orderConfirmationMode", e.target.value as OrderConfirmationMode)}>
              {CONFIRMATION_MODES.map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </Select>
          </Field>
          <p className="mt-1 text-xs text-ink-3">{CONFIRMATION_MODES.find((m) => m.value === values.orderConfirmationMode)?.hint}</p>
        </div>
        {intField("maxPendingBatchesPerSession", "Tối đa đợt chờ xác nhận mỗi phiên", "đợt")}
        {intField("maxItemQuantity", "Số lượng tối đa mỗi dòng món", "phần")}
        {intField("joinRateLimitPerMinute", "Giới hạn quét QR vào bàn", "lần/phút", "Theo địa chỉ IP.")}
        {intField("orderRateLimitPerMinute", "Giới hạn gửi đơn mỗi phiên", "lần/phút")}
        <ToggleRow
          label="Yêu cầu mã PIN bàn"
          description="Khách phải nhập mã PIN 4 số (do nhân viên đọc) trước khi gửi order, chống đặt món từ xa."
          checked={values.requireTablePin}
          onChange={(v) => set("requireTablePin", v)}
        />
      </Section>

      <Section title="Thông số cố định" description="Chỉ đọc; được khóa ở cơ sở dữ liệu.">
        <div>
          <p className="text-[13px] text-ink-3">Múi giờ</p>
          <p className="font-medium">{data.timezone}</p>
        </div>
        <div>
          <p className="text-[13px] text-ink-3">Làm tròn tiền</p>
          <p className="font-medium">{data.moneyRoundingMode}</p>
        </div>
        <div>
          <p className="text-[13px] text-ink-3">Giá đã gồm VAT</p>
          <p className="font-medium">{data.pricesIncludeVat ? "Có" : "Không"}</p>
        </div>
        <div>
          <p className="text-[13px] text-ink-3">Cập nhật lần cuối</p>
          <p className="font-medium">{formatDateTime(data.updatedAt)}</p>
        </div>
      </Section>

      <div className="sticky bottom-0 -mx-4 flex items-center justify-end gap-2 border-t border-line bg-bg/95 px-4 py-3 backdrop-blur-sm md:-mx-8 md:px-8">
        {dirty && <span className="mr-auto text-[13px] text-warn">Có thay đổi chưa lưu</span>}
        <Button
          disabled={!dirty || update.isPending}
          onClick={() => {
            setValues(initial);
            setErrors({});
            setApiError(null);
          }}
        >
          Hoàn tác
        </Button>
        <Button type="submit" variant="primary" loading={update.isPending} disabled={!dirty}>
          Lưu cấu hình
        </Button>
      </div>
    </form>
  );
}

export function SettingsPage() {
  const query = useSettings();
  return (
    <>
      <PageHeader title="Cấu hình nhà hàng" description="Thuế, phí phục vụ, quy tắc đặt món và giới hạn hệ thống." />
      <QueryBoundary
        query={query}
        skeleton={
          <div className="space-y-6" role="status" aria-label="Đang tải cấu hình">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-44 w-full" />
            ))}
          </div>
        }
      >
        {/* key = version: sau khi lưu hoặc tải lại, form khởi tạo lại từ dữ liệu mới nhất. */}
        {(data) => <SettingsForm key={data.version} data={data} />}
      </QueryBoundary>
    </>
  );
}
