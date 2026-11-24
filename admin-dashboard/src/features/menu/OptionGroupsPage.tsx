"use client";

import { useState } from "react";
import type { OptionGroupDto, OptionItemDto, SelectionType } from "@/lib/api/types";
import { describeError, type ErrorInfo } from "@/lib/errors";
import { formatVnd } from "@/lib/format";
import { useForm } from "@/hooks/useForm";
import { useDebounce } from "@/hooks/useDebounce";
import { toast } from "@/stores/toast";
import { Button } from "@/components/ui/Button";
import { Pagination } from "@/components/ui/DataTable";
import { Badge, EmptyState, QueryBoundary, Skeleton } from "@/components/ui/Feedback";
import { Checkbox, Field, Input, Select, Switch } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { Modal } from "@/components/ui/Modal";
import { PageHeader, Toolbar } from "@/components/ui/PageHeader";
import { useCreateOptionGroup, useCreateOptionItem, useOptionGroups, useSetOptionGroupActive, useSetOptionItemActive, useUpdateOptionGroup, useUpdateOptionItem } from "./api";
import { SELECTION_LABEL } from "./labels";
import { validateOptionRules } from "./optionRules";

const PAGE_SIZE = 10;

// ------------------------------------------------------------------------------------------------ Nhóm

interface GroupForm {
  name: string;
  selectionType: SelectionType;
  required: boolean;
  minSelect: string;
  maxSelect: string;
  displayOrder: string;
}

function GroupModal({ group, onClose }: { group: OptionGroupDto | null; onClose: () => void }) {
  const create = useCreateOptionGroup();
  const update = useUpdateOptionGroup();
  const pending = create.isPending || update.isPending;
  const { values, errors, set, setErrors } = useForm<GroupForm>({
    name: group?.name ?? "",
    selectionType: group?.selectionType ?? "SINGLE",
    required: group?.required ?? false,
    minSelect: group ? String(group.minSelect) : "",
    maxSelect: group?.maxSelect != null ? String(group.maxSelect) : "",
    displayOrder: group ? String(group.displayOrder) : "",
  });
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);

  async function submit() {
    const e: Partial<Record<keyof GroupForm, string>> = { ...validateOptionRules(values) };
    if (!values.name.trim()) e.name = "Vui lòng nhập tên nhóm.";
    else if (values.name.trim().length > 100) e.name = "Tên tối đa 100 ký tự.";
    const orderRaw = values.displayOrder.trim();
    if (orderRaw !== "" && !/^\d+$/.test(orderRaw)) e.displayOrder = "Nhập số nguyên từ 0 trở lên.";
    if (group && orderRaw === "") e.displayOrder = "Bắt buộc.";
    setErrors(e);
    if (Object.keys(e).length > 0) return;

    setApiError(null);
    const base = {
      name: values.name.trim(),
      selectionType: values.selectionType,
      required: values.required,
      minSelect: values.minSelect.trim() === "" ? undefined : Number(values.minSelect),
      maxSelect: values.maxSelect.trim() === "" ? undefined : Number(values.maxSelect),
    };
    try {
      if (group) {
        await update.mutateAsync({ id: group.id, ...base, displayOrder: Number(orderRaw), version: group.version });
        toast.success("Đã cập nhật nhóm tùy chọn.");
      } else {
        await create.mutateAsync({ ...base, displayOrder: orderRaw === "" ? undefined : Number(orderRaw) });
        toast.success("Đã thêm nhóm tùy chọn.");
      }
      onClose();
    } catch (err) {
      const info = describeError(err);
      setErrors({ name: info.fields.name, minSelect: info.fields.minSelect, maxSelect: info.fields.maxSelect, displayOrder: info.fields.displayOrder });
      setApiError(info);
    }
  }

  return (
    <Modal
      open
      title={group ? "Sửa nhóm tùy chọn" : "Thêm nhóm tùy chọn"}
      onClose={onClose}
      dismissible={!pending}
      footer={
        <>
          <Button onClick={onClose} disabled={pending}>
            Hủy
          </Button>
          <Button variant="primary" loading={pending} onClick={() => void submit()}>
            {group ? "Lưu" : "Thêm nhóm"}
          </Button>
        </>
      }
    >
      <form
        className="space-y-4"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <FormError info={apiError} onReload={onClose} hasFieldErrors={Object.values(errors).some(Boolean)} />
        <Field label="Tên nhóm" required error={errors.name}>
          <Input data-autofocus value={values.name} onChange={(e) => set("name", e.target.value)} />
        </Field>
        <Field label="Kiểu chọn" required>
          <Select value={values.selectionType} onChange={(e) => set("selectionType", e.target.value as SelectionType)}>
            <option value="SINGLE">{SELECTION_LABEL.SINGLE}</option>
            <option value="MULTIPLE">{SELECTION_LABEL.MULTIPLE}</option>
          </Select>
        </Field>
        <Checkbox checked={values.required} onChange={(v) => set("required", v)} label="Bắt buộc chọn" description="Khách phải chọn ít nhất một lựa chọn trước khi đặt món." />
        <div className="grid grid-cols-2 gap-4">
          <Field label="Chọn tối thiểu" error={errors.minSelect} hint="Để trống: theo “Bắt buộc”.">
            <Input inputMode="numeric" value={values.minSelect} onChange={(e) => set("minSelect", e.target.value)} />
          </Field>
          <Field label="Chọn tối đa" error={errors.maxSelect} hint="Để trống: một (chọn một) hoặc không giới hạn.">
            <Input inputMode="numeric" value={values.maxSelect} onChange={(e) => set("maxSelect", e.target.value)} />
          </Field>
        </div>
        <Field label="Thứ tự hiển thị" required={!!group} error={errors.displayOrder} hint={group ? undefined : "Để trống để đặt cuối."}>
          <Input inputMode="numeric" value={values.displayOrder} onChange={(e) => set("displayOrder", e.target.value)} />
        </Field>
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------------------------------------ Lựa chọn

interface ItemForm {
  name: string;
  extraPrice: string;
  displayOrder: string;
}

function ItemModal({ group, item, onClose }: { group: OptionGroupDto; item: OptionItemDto | null; onClose: () => void }) {
  const create = useCreateOptionItem();
  const update = useUpdateOptionItem();
  const pending = create.isPending || update.isPending;
  const { values, errors, set, setErrors } = useForm<ItemForm>({
    name: item?.name ?? "",
    extraPrice: item ? String(item.extraPrice) : "0",
    displayOrder: item ? String(item.displayOrder) : "",
  });
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);

  async function submit() {
    const e: Partial<Record<keyof ItemForm, string>> = {};
    if (!values.name.trim()) e.name = "Vui lòng nhập tên lựa chọn.";
    else if (values.name.trim().length > 100) e.name = "Tên tối đa 100 ký tự.";
    const priceRaw = values.extraPrice.trim().replace(/[.,\s]/g, "");
    if (!/^\d+$/.test(priceRaw)) e.extraPrice = "Phụ thu phải là số nguyên không âm (VND).";
    const orderRaw = values.displayOrder.trim();
    if (orderRaw !== "" && !/^\d+$/.test(orderRaw)) e.displayOrder = "Nhập số nguyên từ 0 trở lên.";
    if (item && orderRaw === "") e.displayOrder = "Bắt buộc.";
    setErrors(e);
    if (Object.keys(e).length > 0) return;

    setApiError(null);
    try {
      if (item) {
        await update.mutateAsync({ groupId: group.id, id: item.id, name: values.name.trim(), extraPrice: Number(priceRaw), displayOrder: Number(orderRaw), version: item.version });
        toast.success("Đã cập nhật lựa chọn.");
      } else {
        await create.mutateAsync({ groupId: group.id, name: values.name.trim(), extraPrice: Number(priceRaw), displayOrder: orderRaw === "" ? undefined : Number(orderRaw) });
        toast.success("Đã thêm lựa chọn.");
      }
      onClose();
    } catch (err) {
      const info = describeError(err);
      setErrors({ name: info.fields.name, extraPrice: info.fields.extraPrice, displayOrder: info.fields.displayOrder });
      setApiError(info);
    }
  }

  return (
    <Modal
      open
      title={item ? "Sửa lựa chọn" : "Thêm lựa chọn"}
      description={`Nhóm: ${group.name}`}
      onClose={onClose}
      dismissible={!pending}
      footer={
        <>
          <Button onClick={onClose} disabled={pending}>
            Hủy
          </Button>
          <Button variant="primary" loading={pending} onClick={() => void submit()}>
            {item ? "Lưu" : "Thêm lựa chọn"}
          </Button>
        </>
      }
    >
      <form
        className="space-y-4"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <FormError info={apiError} onReload={onClose} hasFieldErrors={Object.values(errors).some(Boolean)} />
        <Field label="Tên lựa chọn" required error={errors.name}>
          <Input data-autofocus value={values.name} onChange={(e) => set("name", e.target.value)} />
        </Field>
        <Field label="Phụ thu (VND)" required error={errors.extraPrice} hint="Nhập 0 nếu không phụ thu.">
          <Input inputMode="numeric" value={values.extraPrice} onChange={(e) => set("extraPrice", e.target.value)} />
        </Field>
        <Field label="Thứ tự hiển thị" required={!!item} error={errors.displayOrder} hint={item ? undefined : "Để trống để đặt cuối."}>
          <Input inputMode="numeric" value={values.displayOrder} onChange={(e) => set("displayOrder", e.target.value)} />
        </Field>
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------------------------------------ Thẻ nhóm

function GroupCard({ group, onEdit, onAddItem, onEditItem }: { group: OptionGroupDto; onEdit: () => void; onAddItem: () => void; onEditItem: (i: OptionItemDto) => void }) {
  const setGroupActive = useSetOptionGroupActive();
  const setItemActive = useSetOptionItemActive();
  const [busy, setBusy] = useState<string | null>(null);

  async function toggleGroup(next: boolean) {
    setBusy(`g${group.id}`);
    try {
      await setGroupActive.mutateAsync({ id: group.id, active: next, version: group.version });
      toast.success(next ? "Đã bật nhóm." : "Đã tắt nhóm.");
    } catch (e) {
      toast.error(describeError(e).message);
    } finally {
      setBusy(null);
    }
  }

  async function toggleItem(i: OptionItemDto, next: boolean) {
    setBusy(`i${i.id}`);
    try {
      await setItemActive.mutateAsync({ groupId: group.id, id: i.id, active: next, version: i.version });
      toast.success(next ? "Đã bật lựa chọn." : "Đã tắt lựa chọn.");
    } catch (e) {
      toast.error(describeError(e).message);
    } finally {
      setBusy(null);
    }
  }

  const range = `${group.minSelect}–${group.maxSelect ?? "không giới hạn"}`;
  return (
    <section className="rounded-card border border-line bg-surface" aria-label={`Nhóm ${group.name}`}>
      <header className="flex flex-wrap items-center gap-3 border-b border-line px-4 py-3">
        <div className="min-w-0 flex-1">
          <h3 className="font-semibold">{group.name}</h3>
          <p className="mt-0.5 flex flex-wrap items-center gap-2 text-[13px] text-ink-2">
            <span>{SELECTION_LABEL[group.selectionType]}</span>
            <span aria-hidden>·</span>
            <span>Chọn {range}</span>
            {group.required && <Badge tone="warn">Bắt buộc</Badge>}
            {!group.active && <Badge>Đang tắt</Badge>}
          </p>
        </div>
        <Switch checked={group.active} loading={busy === `g${group.id}`} onChange={(v) => void toggleGroup(v)} label={`Bật nhóm ${group.name}`} />
        <Button size="sm" onClick={onEdit}>
          Sửa nhóm
        </Button>
        <Button size="sm" onClick={onAddItem}>
          Thêm lựa chọn
        </Button>
      </header>
      {group.items.length === 0 ? (
        <p className="px-4 py-4 text-[13px] text-ink-3">Nhóm chưa có lựa chọn nào.</p>
      ) : (
        <ul className="divide-y divide-line">
          {[...group.items]
            .sort((a, b) => a.displayOrder - b.displayOrder || a.id - b.id)
            .map((i) => (
              <li key={i.id} className="flex items-center gap-3 px-4 py-2.5">
                <span className={i.active ? "min-w-0 flex-1 truncate" : "min-w-0 flex-1 truncate text-ink-3 line-through"}>{i.name}</span>
                <span className="w-28 text-right tabular-nums text-ink-2">{i.extraPrice > 0 ? `+${formatVnd(i.extraPrice)}` : "Miễn phí"}</span>
                <Switch checked={i.active} loading={busy === `i${i.id}`} onChange={(v) => void toggleItem(i, v)} label={`Bật lựa chọn ${i.name}`} />
                <Button size="sm" onClick={() => onEditItem(i)} aria-label={`Sửa lựa chọn ${i.name}`}>
                  Sửa
                </Button>
              </li>
            ))}
        </ul>
      )}
    </section>
  );
}

// ------------------------------------------------------------------------------------------------ Trang

type Dialog = { kind: "group"; group: OptionGroupDto | null } | { kind: "item"; group: OptionGroupDto; item: OptionItemDto | null };

export function OptionGroupsPage() {
  const [q, setQ] = useState("");
  const [active, setActive] = useState<"" | "true" | "false">("");
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const dq = useDebounce(q.trim());
  const query = useOptionGroups({ q: dq, active, page, size: PAGE_SIZE });
  const filtered = !!(dq || active);

  return (
    <>
      <PageHeader
        title="Option & Topping"
        description="Nhóm tùy chọn gắn vào món (size, đường, đá, topping…). Không xóa: dùng bật/tắt."
        actions={
          <Button variant="primary" onClick={() => setDialog({ kind: "group", group: null })}>
            Thêm nhóm
          </Button>
        }
      />
      <Toolbar>
        <div className="w-64">
          <Input
            placeholder="Tìm theo tên nhóm"
            aria-label="Tìm nhóm tùy chọn"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setPage(0);
            }}
          />
        </div>
        <div className="w-44">
          <Select
            aria-label="Lọc theo trạng thái"
            value={active}
            onChange={(e) => {
              setActive(e.target.value as "" | "true" | "false");
              setPage(0);
            }}
          >
            <option value="">Tất cả</option>
            <option value="true">Đang bật</option>
            <option value="false">Đang tắt</option>
          </Select>
        </div>
      </Toolbar>
      <QueryBoundary
        query={query}
        skeleton={
          <div className="space-y-3" role="status" aria-label="Đang tải">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-28 w-full" />
            ))}
          </div>
        }
        isEmpty={(d) => d.items.length === 0}
        empty={
          <EmptyState
            title={filtered ? "Không có nhóm phù hợp" : "Chưa có nhóm tùy chọn"}
            description={filtered ? "Thử đổi từ khóa hoặc bộ lọc." : "Tạo nhóm đầu tiên, ví dụ “Size” hoặc “Topping”."}
            action={!filtered && <Button variant="primary" onClick={() => setDialog({ kind: "group", group: null })}>Thêm nhóm</Button>}
          />
        }
      >
        {(data) => (
          <div className="space-y-3">
            {data.items.map((g) => (
              <GroupCard
                key={g.id}
                group={g}
                onEdit={() => setDialog({ kind: "group", group: g })}
                onAddItem={() => setDialog({ kind: "item", group: g, item: null })}
                onEditItem={(item) => setDialog({ kind: "item", group: g, item })}
              />
            ))}
            <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onPage={setPage} />
          </div>
        )}
      </QueryBoundary>
      {dialog?.kind === "group" && <GroupModal group={dialog.group} onClose={() => setDialog(null)} />}
      {dialog?.kind === "item" && <ItemModal group={dialog.group} item={dialog.item} onClose={() => setDialog(null)} />}
    </>
  );
}
