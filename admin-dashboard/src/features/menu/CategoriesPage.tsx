"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import type { CategoryDto } from "@/lib/api/types";
import { describeError, type ErrorInfo } from "@/lib/errors";
import { formatDateTime } from "@/lib/format";
import { useForm } from "@/hooks/useForm";
import { useDebounce } from "@/hooks/useDebounce";
import { toast } from "@/stores/toast";
import { Button } from "@/components/ui/Button";
import { DataTable, Pagination, type Column } from "@/components/ui/DataTable";
import { EmptyState, QueryBoundary, Skeleton, TableSkeleton } from "@/components/ui/Feedback";
import { Field, Input, Select, Switch, Textarea } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { Modal } from "@/components/ui/Modal";
import { PageHeader, Toolbar } from "@/components/ui/PageHeader";
import { RowMenu } from "@/components/ui/RowMenu";
import { ErrorState } from "@/components/ui/Feedback";
import { useAllCategories, useCategories, useCreateCategory, useReorderCategories, useSetCategoryVisibility, useUpdateCategory } from "./api";

const PAGE_SIZE = 15;

// ------------------------------------------------------------------------------------------------ Form thêm / sửa

interface CategoryForm {
  name: string;
  description: string;
  displayOrder: string;
  visible: boolean;
}

function CategoryModal({ category, onClose }: { category: CategoryDto | null; onClose: () => void }) {
  const create = useCreateCategory();
  const update = useUpdateCategory();
  const pending = create.isPending || update.isPending;
  const { values, errors, set, setErrors } = useForm<CategoryForm>({
    name: category?.name ?? "",
    description: category?.description ?? "",
    displayOrder: category ? String(category.displayOrder) : "",
    visible: category?.visible ?? true,
  });
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);

  async function submit() {
    const e: Partial<Record<keyof CategoryForm, string>> = {};
    if (!values.name.trim()) e.name = "Vui lòng nhập tên danh mục.";
    else if (values.name.trim().length > 100) e.name = "Tên tối đa 100 ký tự.";
    if (values.description.length > 2000) e.description = "Mô tả tối đa 2000 ký tự.";
    const orderRaw = values.displayOrder.trim();
    if (orderRaw !== "" && !/^\d+$/.test(orderRaw)) e.displayOrder = "Nhập số nguyên từ 0 trở lên.";
    if (category && orderRaw === "") e.displayOrder = "Bắt buộc.";
    setErrors(e);
    if (Object.keys(e).length > 0) return;

    setApiError(null);
    try {
      const base = { name: values.name.trim(), description: values.description.trim() || undefined };
      if (category) {
        await update.mutateAsync({ id: category.id, ...base, displayOrder: Number(orderRaw), version: category.version });
        toast.success("Đã cập nhật danh mục.");
      } else {
        await create.mutateAsync({ ...base, displayOrder: orderRaw === "" ? undefined : Number(orderRaw), visible: values.visible });
        toast.success("Đã thêm danh mục.");
      }
      onClose();
    } catch (err) {
      const info = describeError(err);
      setErrors({ name: info.fields.name, description: info.fields.description, displayOrder: info.fields.displayOrder });
      setApiError(info);
    }
  }

  return (
    <Modal
      open
      title={category ? "Sửa danh mục" : "Thêm danh mục"}
      onClose={onClose}
      dismissible={!pending}
      footer={
        <>
          <Button onClick={onClose} disabled={pending}>
            Hủy
          </Button>
          <Button variant="primary" loading={pending} onClick={() => void submit()}>
            {category ? "Lưu" : "Thêm danh mục"}
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
        <Field label="Tên danh mục" required error={errors.name}>
          <Input data-autofocus value={values.name} onChange={(e) => set("name", e.target.value)} />
        </Field>
        <Field label="Mô tả" error={errors.description}>
          <Textarea value={values.description} onChange={(e) => set("description", e.target.value)} />
        </Field>
        <Field label="Thứ tự hiển thị" required={!!category} error={errors.displayOrder} hint={category ? undefined : "Để trống để đặt cuối danh sách."}>
          <Input inputMode="numeric" value={values.displayOrder} onChange={(e) => set("displayOrder", e.target.value)} />
        </Field>
        {!category && (
          <div className="flex items-center justify-between gap-4">
            <div>
              <p className="font-medium">Hiển thị cho khách</p>
              <p className="text-[13px] text-ink-2">Danh mục ẩn không xuất hiện trên menu của khách.</p>
            </div>
            <Switch checked={values.visible} onChange={(v) => set("visible", v)} label="Hiển thị cho khách" />
          </div>
        )}
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------------------------------------ Sắp xếp thứ tự

function ReorderModal({ onClose }: { onClose: () => void }) {
  const all = useAllCategories();
  const reorder = useReorderCategories();
  const qc = useQueryClient();
  const [order, setOrder] = useState<CategoryDto[] | null>(null);
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);

  // Lấy danh sách ban đầu một lần; sau đó người dùng sắp xếp trên bản sao cục bộ.
  const list = order ?? (all.data ? [...all.data].sort((a, b) => a.displayOrder - b.displayOrder || a.id - b.id) : null);

  function move(i: number, d: -1 | 1) {
    if (!list) return;
    const j = i + d;
    if (j < 0 || j >= list.length) return;
    const next = [...list];
    [next[i], next[j]] = [next[j]!, next[i]!];
    setOrder(next);
  }

  async function save() {
    if (!list) return;
    setApiError(null);
    try {
      // Gán lại thứ tự 1..n để không còn giá trị trùng nhau.
      await reorder.mutateAsync(list.map((c, i) => ({ id: c.id, displayOrder: i + 1, version: c.version })));
      toast.success("Đã lưu thứ tự hiển thị.");
      onClose();
    } catch (e) {
      setApiError(describeError(e));
    }
  }

  return (
    <Modal
      open
      title="Sắp xếp thứ tự hiển thị"
      description="Dùng nút mũi tên để đổi vị trí. Thứ tự này áp dụng cho menu của khách."
      onClose={onClose}
      dismissible={!reorder.isPending}
      footer={
        <>
          <Button onClick={onClose} disabled={reorder.isPending}>
            Hủy
          </Button>
          <Button variant="primary" loading={reorder.isPending} disabled={!list || list.length === 0 || order === null} onClick={() => void save()}>
            Lưu thứ tự
          </Button>
        </>
      }
    >
      <FormError
        info={apiError}
        onReload={() => {
          setOrder(null);
          setApiError(null);
          void qc.invalidateQueries({ queryKey: ["categories"] });
        }}
      />
      {all.isPending && (
        <div className="space-y-2" role="status" aria-label="Đang tải">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      )}
      {all.isError && <ErrorState compact error={all.error} onRetry={() => void all.refetch()} />}
      {list && list.length === 0 && <EmptyState title="Chưa có danh mục để sắp xếp" />}
      {list && list.length > 0 && (
        <ol className="space-y-1.5">
          {list.map((c, i) => (
            <li key={c.id} className="flex items-center gap-3 rounded-ctl border border-line px-3 py-2">
              <span className="w-6 text-center text-xs text-ink-3">{i + 1}</span>
              <span className="min-w-0 flex-1 truncate font-medium">{c.name}</span>
              {!c.visible && <span className="text-xs text-ink-3">Đang ẩn</span>}
              <Button size="sm" aria-label={`Đưa ${c.name} lên`} disabled={i === 0} onClick={() => move(i, -1)}>
                ↑
              </Button>
              <Button size="sm" aria-label={`Đưa ${c.name} xuống`} disabled={i === list.length - 1} onClick={() => move(i, 1)}>
                ↓
              </Button>
            </li>
          ))}
        </ol>
      )}
    </Modal>
  );
}

// ------------------------------------------------------------------------------------------------ Trang

export function CategoriesPage() {
  const [q, setQ] = useState("");
  const [visible, setVisible] = useState<"" | "true" | "false">("");
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<{ kind: "form"; category: CategoryDto | null } | { kind: "reorder" } | null>(null);
  const dq = useDebounce(q.trim());
  const query = useCategories({ q: dq, visible, page, size: PAGE_SIZE });
  const setVis = useSetCategoryVisibility();
  const [busyId, setBusyId] = useState<number | null>(null);
  const filtered = !!(dq || visible);

  async function toggle(c: CategoryDto, next: boolean) {
    setBusyId(c.id);
    try {
      await setVis.mutateAsync({ id: c.id, visible: next, version: c.version });
      toast.success(next ? `Đã hiện "${c.name}" trên menu.` : `Đã ẩn "${c.name}" khỏi menu.`);
    } catch (e) {
      toast.error(describeError(e).message);
    } finally {
      setBusyId(null);
    }
  }

  const columns: Column<CategoryDto>[] = [
    { key: "order", header: "Thứ tự", className: "w-20", cell: (c) => <span className="text-ink-2">{c.displayOrder}</span> },
    {
      key: "name",
      header: "Danh mục",
      cell: (c) => (
        <div className="max-w-md">
          <p className="font-medium">{c.name}</p>
          {c.description && <p className="truncate text-xs text-ink-3">{c.description}</p>}
        </div>
      ),
    },
    {
      key: "visible",
      header: "Hiển thị",
      cell: (c) => <Switch checked={c.visible} loading={busyId === c.id} onChange={(v) => void toggle(c, v)} label={`Hiển thị ${c.name}`} />,
    },
    { key: "updated", header: "Cập nhật", cell: (c) => <span className="text-ink-2">{formatDateTime(c.updatedAt)}</span> },
    {
      key: "act",
      header: "",
      align: "right",
      className: "w-12",
      cell: (c) => <RowMenu label={`Thao tác với ${c.name}`} items={[{ label: "Sửa", onSelect: () => setDialog({ kind: "form", category: c }) }]} />,
    },
  ];

  return (
    <>
      <PageHeader
        title="Danh mục"
        description="Nhóm món trên menu. Không xóa: dùng ẩn/hiện."
        actions={
          <>
            <Button onClick={() => setDialog({ kind: "reorder" })}>Sắp xếp thứ tự</Button>
            <Button variant="primary" onClick={() => setDialog({ kind: "form", category: null })}>
              Thêm danh mục
            </Button>
          </>
        }
      />
      <Toolbar>
        <div className="w-64">
          <Input
            placeholder="Tìm theo tên danh mục"
            aria-label="Tìm danh mục"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setPage(0);
            }}
          />
        </div>
        <div className="w-44">
          <Select
            aria-label="Lọc theo hiển thị"
            value={visible}
            onChange={(e) => {
              setVisible(e.target.value as "" | "true" | "false");
              setPage(0);
            }}
          >
            <option value="">Tất cả</option>
            <option value="true">Đang hiển thị</option>
            <option value="false">Đang ẩn</option>
          </Select>
        </div>
      </Toolbar>
      <QueryBoundary
        query={query}
        skeleton={<TableSkeleton cols={5} />}
        isEmpty={(d) => d.items.length === 0}
        empty={
          <EmptyState
            title={filtered ? "Không có danh mục phù hợp" : "Chưa có danh mục nào"}
            description={filtered ? "Thử đổi từ khóa hoặc bộ lọc." : "Tạo danh mục đầu tiên để bắt đầu xây dựng menu."}
            action={!filtered && <Button variant="primary" onClick={() => setDialog({ kind: "form", category: null })}>Thêm danh mục</Button>}
          />
        }
      >
        {(data) => (
          <>
            <DataTable caption="Danh sách danh mục" columns={columns} rows={data.items} rowKey={(c) => c.id} />
            <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onPage={setPage} />
          </>
        )}
      </QueryBoundary>
      {dialog?.kind === "form" && <CategoryModal category={dialog.category} onClose={() => setDialog(null)} />}
      {dialog?.kind === "reorder" && <ReorderModal onClose={() => setDialog(null)} />}
    </>
  );
}
