"use client";

import { useRef, useState } from "react";
import type { CategoryDto, ProductDto, ProductStatus } from "@/lib/api/types";
import { describeError, type ErrorInfo } from "@/lib/errors";
import { env } from "@/lib/env";
import { formatVnd } from "@/lib/format";
import { useForm } from "@/hooks/useForm";
import { useDebounce } from "@/hooks/useDebounce";
import { toast } from "@/stores/toast";
import { Badge, Banner, EmptyState, ErrorState, QueryBoundary, Skeleton, TableSkeleton } from "@/components/ui/Feedback";
import { Button } from "@/components/ui/Button";
import { DataTable, Pagination, type Column } from "@/components/ui/DataTable";
import { Checkbox, Field, Input, Select, Textarea } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { Modal } from "@/components/ui/Modal";
import { PageHeader, Toolbar } from "@/components/ui/PageHeader";
import { ReasonDialog } from "@/components/ui/ReasonDialog";
import { RowMenu } from "@/components/ui/RowMenu";
import { Tabs } from "@/components/ui/Tabs";
import {
  useAllCategories,
  useAllOptionGroups,
  useChangeProductStatus,
  useCreateProduct,
  useProduct,
  useProducts,
  useRemoveProductImage,
  useSetProductOptionGroups,
  useUpdateProduct,
  useUploadProductImage,
} from "./api";
import { PRODUCT_STATUSES, PRODUCT_STATUS_LABEL, PRODUCT_STATUS_TONE, SELECTION_LABEL } from "./labels";

const PAGE_SIZE = 15;
const IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"];

/** Chuỗi số nguyên không âm (giá VND không có phần thập phân). */
function parsePrice(raw: string): number | null {
  const s = raw.trim().replace(/[.,\s]/g, "");
  if (!/^\d+$/.test(s)) return null;
  const n = Number(s);
  return Number.isSafeInteger(n) ? n : null;
}

// ------------------------------------------------------------------------------------------------ Thông tin món

interface InfoForm {
  categoryId: string;
  name: string;
  description: string;
  price: string;
  displayOrder: string;
}

function InfoSection({ product, categories, onCreated, onClose }: { product: ProductDto | null; categories: CategoryDto[]; onCreated: (p: ProductDto) => void; onClose: () => void }) {
  const create = useCreateProduct();
  const update = useUpdateProduct();
  const pending = create.isPending || update.isPending;
  const { values, errors, set, setErrors } = useForm<InfoForm>({
    categoryId: product ? String(product.categoryId) : "",
    name: product?.name ?? "",
    description: product?.description ?? "",
    price: product ? String(product.price) : "",
    displayOrder: product ? String(product.displayOrder) : "",
  });
  const [apiError, setApiError] = useState<ErrorInfo | null>(null);

  async function submit() {
    const e: Partial<Record<keyof InfoForm, string>> = {};
    if (!values.categoryId) e.categoryId = "Vui lòng chọn danh mục.";
    if (!values.name.trim()) e.name = "Vui lòng nhập tên món.";
    else if (values.name.trim().length > 150) e.name = "Tên tối đa 150 ký tự.";
    if (values.description.length > 2000) e.description = "Mô tả tối đa 2000 ký tự.";
    const price = parsePrice(values.price);
    if (price === null) e.price = "Giá phải là số nguyên không âm (VND).";
    const orderRaw = values.displayOrder.trim();
    if (orderRaw !== "" && !/^\d+$/.test(orderRaw)) e.displayOrder = "Nhập số nguyên từ 0 trở lên.";
    if (product && orderRaw === "") e.displayOrder = "Bắt buộc.";
    setErrors(e);
    if (Object.keys(e).length > 0 || price === null) return;

    setApiError(null);
    const base = { categoryId: Number(values.categoryId), name: values.name.trim(), description: values.description.trim() || undefined, price };
    try {
      if (product) {
        await update.mutateAsync({ id: product.id, ...base, displayOrder: Number(orderRaw), version: product.version });
        toast.success("Đã cập nhật món.");
      } else {
        const created = await create.mutateAsync({ ...base, displayOrder: orderRaw === "" ? undefined : Number(orderRaw) });
        toast.success("Đã thêm món. Bạn có thể thêm ảnh và nhóm tùy chọn.");
        onCreated(created);
      }
    } catch (err) {
      const info = describeError(err);
      setErrors({ categoryId: info.fields.categoryId, name: info.fields.name, description: info.fields.description, price: info.fields.price, displayOrder: info.fields.displayOrder });
      setApiError(info);
    }
  }

  return (
    <form
      className="space-y-4"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <FormError info={apiError} onReload={onClose} hasFieldErrors={Object.values(errors).some(Boolean)} />
      <Field label="Danh mục" required error={errors.categoryId}>
        <Select value={values.categoryId} onChange={(e) => set("categoryId", e.target.value)}>
          <option value="">Chọn danh mục</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
              {c.visible ? "" : " (đang ẩn)"}
            </option>
          ))}
        </Select>
      </Field>
      <Field label="Tên món" required error={errors.name}>
        <Input data-autofocus value={values.name} onChange={(e) => set("name", e.target.value)} />
      </Field>
      <Field label="Mô tả" error={errors.description}>
        <Textarea value={values.description} onChange={(e) => set("description", e.target.value)} />
      </Field>
      <div className="grid grid-cols-2 gap-4">
        <Field label="Giá bán (VND)" required error={errors.price}>
          <Input inputMode="numeric" value={values.price} onChange={(e) => set("price", e.target.value)} />
        </Field>
        <Field label="Thứ tự hiển thị" required={!!product} error={errors.displayOrder} hint={product ? undefined : "Để trống để đặt cuối."}>
          <Input inputMode="numeric" value={values.displayOrder} onChange={(e) => set("displayOrder", e.target.value)} />
        </Field>
      </div>
      {product && <p className="text-[13px] text-ink-3">Giá mới chỉ áp dụng cho đơn mới; các món đã đặt giữ giá tại thời điểm gọi.</p>}
      <div className="flex justify-end gap-2 pt-2">
        <Button onClick={onClose} disabled={pending}>
          Đóng
        </Button>
        <Button type="submit" variant="primary" loading={pending}>
          {product ? "Lưu thông tin" : "Thêm món"}
        </Button>
      </div>
    </form>
  );
}

// ------------------------------------------------------------------------------------------------ Ảnh món

function ImageSection({ product }: { product: ProductDto }) {
  const upload = useUploadProductImage();
  const remove = useRemoveProductImage();
  const inputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<ErrorInfo | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);
  const busy = upload.isPending || remove.isPending;

  async function onPick(file: File | undefined) {
    if (!file) return;
    setLocalError(null);
    setError(null);
    if (!IMAGE_TYPES.includes(file.type)) {
      setLocalError("Chỉ chấp nhận ảnh JPEG, PNG hoặc WebP.");
      return;
    }
    if (file.size > env.maxImageSizeMb * 1024 * 1024) {
      setLocalError(`Ảnh vượt quá ${env.maxImageSizeMb}MB.`);
      return;
    }
    try {
      await upload.mutateAsync({ id: product.id, version: product.version, file });
      toast.success("Đã cập nhật ảnh món.");
    } catch (e) {
      setError(describeError(e));
    } finally {
      if (inputRef.current) inputRef.current.value = "";
    }
  }

  async function onRemove() {
    setError(null);
    try {
      await remove.mutateAsync({ id: product.id, version: product.version });
      toast.success("Đã xóa ảnh món.");
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <div className="space-y-4">
      <FormError info={error} onReload={() => setError(null)} />
      {localError && <Banner tone="danger">{localError}</Banner>}
      <div className="flex items-start gap-4">
        <div className="flex h-36 w-36 shrink-0 items-center justify-center overflow-hidden rounded-card border border-line bg-surface-2">
          {product.imageUrl ? (
            // eslint-disable-next-line @next/next/no-img-element -- ảnh Cloudinary, không cần tối ưu qua next/image
            <img src={product.imageUrl} alt={`Ảnh ${product.name}`} className="h-full w-full object-cover" />
          ) : (
            <span className="text-xs text-ink-3">Chưa có ảnh</span>
          )}
        </div>
        <div className="space-y-2">
          <p className="text-[13px] text-ink-2">JPEG, PNG hoặc WebP, tối đa {env.maxImageSizeMb}MB.</p>
          <input ref={inputRef} type="file" accept={IMAGE_TYPES.join(",")} className="hidden" onChange={(e) => void onPick(e.target.files?.[0])} aria-label="Chọn ảnh món" />
          <div className="flex gap-2">
            <Button loading={upload.isPending} disabled={busy} onClick={() => inputRef.current?.click()}>
              {product.imageUrl ? "Đổi ảnh" : "Tải ảnh lên"}
            </Button>
            {product.imageUrl && (
              <Button variant="danger" loading={remove.isPending} disabled={busy} onClick={() => void onRemove()}>
                Xóa ảnh
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ Nhóm option của món

function OptionGroupsSection({ product }: { product: ProductDto }) {
  const groups = useAllOptionGroups();
  const save = useSetProductOptionGroups();
  const assigned = (product.optionGroups ?? []).map((g) => g.optionGroupId);
  const [selected, setSelected] = useState<number[]>(assigned);
  const [error, setError] = useState<ErrorInfo | null>(null);
  const dirty = selected.length !== assigned.length || selected.some((id) => !assigned.includes(id));

  function toggle(id: number, on: boolean) {
    setSelected((s) => (on ? [...s, id] : s.filter((x) => x !== id)));
  }

  async function submit() {
    setError(null);
    try {
      await save.mutateAsync({ id: product.id, optionGroupIds: selected, version: product.version });
      toast.success("Đã cập nhật nhóm tùy chọn của món.");
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <div className="space-y-4">
      <FormError info={error} onReload={() => setError(null)} />
      {groups.isPending && (
        <div className="space-y-2" role="status" aria-label="Đang tải">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      )}
      {groups.isError && <ErrorState compact error={groups.error} onRetry={() => void groups.refetch()} />}
      {groups.data && groups.data.length === 0 && <EmptyState title="Chưa có nhóm tùy chọn" description="Tạo nhóm ở mục Option & Topping, rồi gán cho món tại đây." />}
      {groups.data && groups.data.length > 0 && (
        <ul className="space-y-2">
          {groups.data.map((g) => {
            const wasAssigned = assigned.includes(g.id);
            // Nhóm đã tắt vẫn cho bỏ gán, nhưng không cho gán mới.
            const disabled = !g.active && !wasAssigned;
            return (
              <li key={g.id} className="rounded-ctl border border-line px-3 py-2">
                <Checkbox
                  checked={selected.includes(g.id)}
                  disabled={disabled}
                  onChange={(on) => toggle(g.id, on)}
                  label={g.name}
                  description={`${SELECTION_LABEL[g.selectionType]}${g.required ? " · bắt buộc" : ""}${g.active ? "" : " · đang tắt"} · ${g.items.length} lựa chọn`}
                />
              </li>
            );
          })}
        </ul>
      )}
      <div className="flex justify-end">
        <Button variant="primary" loading={save.isPending} disabled={!dirty} onClick={() => void submit()}>
          Lưu nhóm tùy chọn
        </Button>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ Trình soạn thảo món

type EditorTab = "info" | "image" | "options";

function ProductEditor({ productId, categories, onClose }: { productId: number | null; categories: CategoryDto[]; onClose: () => void }) {
  const [id, setId] = useState<number | null>(productId);
  const [tab, setTab] = useState<EditorTab>("info");
  const detail = useProduct(id);
  const tabs = [
    { id: "info" as const, label: "Thông tin" },
    { id: "image" as const, label: "Ảnh" },
    { id: "options" as const, label: "Tùy chọn" },
  ];

  return (
    <Modal open size="lg" title={productId === null && id === null ? "Thêm món" : "Sửa món"} description={detail.data?.name} onClose={onClose}>
      {id !== null && <Tabs tabs={tabs} value={tab} onChange={setTab} />}
      {id === null && <InfoSection product={null} categories={categories} onCreated={(p) => { setId(p.id); setTab("image"); }} onClose={onClose} />}
      {id !== null && detail.isPending && (
        <div className="space-y-3" role="status" aria-label="Đang tải">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      )}
      {id !== null && detail.isError && <ErrorState compact error={detail.error} onRetry={() => void detail.refetch()} />}
      {detail.data && tab === "info" && (
        // key theo version: form dựng lại với dữ liệu mới nhất sau mỗi lần lưu / tải lại.
        <InfoSection key={detail.data.version} product={detail.data} categories={categories} onCreated={() => undefined} onClose={onClose} />
      )}
      {detail.data && tab === "image" && <ImageSection product={detail.data} />}
      {detail.data && tab === "options" && <OptionGroupsSection key={detail.data.version} product={detail.data} />}
    </Modal>
  );
}

// ------------------------------------------------------------------------------------------------ Trang

export function ProductsPage() {
  const [q, setQ] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [status, setStatus] = useState<ProductStatus | "">("");
  const [page, setPage] = useState(0);
  const [editor, setEditor] = useState<{ id: number | null } | null>(null);
  const [statusTarget, setStatusTarget] = useState<{ product: ProductDto; next: ProductStatus } | null>(null);
  const dq = useDebounce(q.trim());
  const categories = useAllCategories();
  const query = useProducts({ q: dq, categoryId, status, page, size: PAGE_SIZE });
  const changeStatus = useChangeProductStatus();
  const filtered = !!(dq || categoryId || status);
  const catName = (id: number) => categories.data?.find((c) => c.id === id)?.name ?? `#${id}`;

  const columns: Column<ProductDto>[] = [
    {
      key: "name",
      header: "Món",
      cell: (p) => (
        <div className="flex items-center gap-3">
          <div className="h-10 w-10 shrink-0 overflow-hidden rounded-ctl border border-line bg-surface-2">
            {p.imageUrl && (
              // eslint-disable-next-line @next/next/no-img-element -- ảnh Cloudinary
              <img src={p.imageUrl} alt="" className="h-full w-full object-cover" />
            )}
          </div>
          <span className="font-medium">{p.name}</span>
        </div>
      ),
    },
    { key: "category", header: "Danh mục", cell: (p) => <span className="text-ink-2">{catName(p.categoryId)}</span> },
    { key: "price", header: "Giá", align: "right", cell: (p) => <span className="tabular-nums">{formatVnd(p.price)}</span> },
    { key: "status", header: "Trạng thái", cell: (p) => <Badge tone={PRODUCT_STATUS_TONE[p.status]}>{PRODUCT_STATUS_LABEL[p.status]}</Badge> },
    {
      key: "act",
      header: "",
      align: "right",
      className: "w-12",
      cell: (p) => (
        <RowMenu
          label={`Thao tác với ${p.name}`}
          items={[
            { label: "Sửa món", onSelect: () => setEditor({ id: p.id }) },
            ...PRODUCT_STATUSES.filter((s) => s !== p.status).map((s) => ({ label: `Đặt: ${PRODUCT_STATUS_LABEL[s]}`, onSelect: () => setStatusTarget({ product: p, next: s }) })),
          ]}
        />
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Món ăn"
        description="Quản lý món, giá, trạng thái bán và ảnh. Không xóa: dùng trạng thái Đã ẩn."
        actions={
          <Button variant="primary" onClick={() => setEditor({ id: null })} disabled={!categories.data}>
            Thêm món
          </Button>
        }
      />
      <Toolbar>
        <div className="w-64">
          <Input
            placeholder="Tìm theo tên món"
            aria-label="Tìm món"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setPage(0);
            }}
          />
        </div>
        <div className="w-48">
          <Select
            aria-label="Lọc theo danh mục"
            value={categoryId}
            onChange={(e) => {
              setCategoryId(e.target.value);
              setPage(0);
            }}
          >
            <option value="">Mọi danh mục</option>
            {categories.data?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </Select>
        </div>
        <div className="w-44">
          <Select
            aria-label="Lọc theo trạng thái"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as ProductStatus | "");
              setPage(0);
            }}
          >
            <option value="">Mọi trạng thái</option>
            {PRODUCT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {PRODUCT_STATUS_LABEL[s]}
              </option>
            ))}
          </Select>
        </div>
      </Toolbar>
      <QueryBoundary
        query={query}
        skeleton={<TableSkeleton cols={5} />}
        isEmpty={(d) => d.items.length === 0}
        empty={
          <EmptyState
            title={filtered ? "Không có món phù hợp" : "Chưa có món nào"}
            description={filtered ? "Thử đổi từ khóa hoặc bộ lọc." : "Thêm món đầu tiên (cần có ít nhất một danh mục)."}
            action={!filtered && categories.data && <Button variant="primary" onClick={() => setEditor({ id: null })}>Thêm món</Button>}
          />
        }
      >
        {(data) => (
          <>
            <DataTable caption="Danh sách món" columns={columns} rows={data.items} rowKey={(p) => p.id} />
            <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onPage={setPage} />
          </>
        )}
      </QueryBoundary>

      {editor && categories.data && <ProductEditor productId={editor.id} categories={categories.data} onClose={() => setEditor(null)} />}

      {statusTarget && (
        <ReasonDialog
          open
          title={`Đổi trạng thái: ${statusTarget.product.name}`}
          description={`${PRODUCT_STATUS_LABEL[statusTarget.product.status]} → ${PRODUCT_STATUS_LABEL[statusTarget.next]}. ${
            statusTarget.next === "HIDDEN" ? "Khách sẽ không thấy món này trên menu." : statusTarget.next === "SOLD_OUT" ? "Khách vẫn thấy món nhưng không thể đặt." : "Khách có thể đặt món này."
          }`}
          confirmLabel="Xác nhận"
          reason="optional"
          reasonLabel="Lý do (tùy chọn)"
          onSubmit={async (v) => {
            await changeStatus.mutateAsync({ id: statusTarget.product.id, status: statusTarget.next, version: statusTarget.product.version, reason: v.reason.trim() || undefined });
            toast.success("Đã đổi trạng thái món.");
          }}
          onReload={() => setStatusTarget(null)}
          onClose={() => setStatusTarget(null)}
        />
      )}
    </>
  );
}
