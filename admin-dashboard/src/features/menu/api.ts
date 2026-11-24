import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, request } from "@/lib/api/client";
import { cleanParams } from "@/lib/api/query";
import type { CategoryDto, OptionGroupDto, OptionItemDto, Page, ProductDto, ProductStatus, SelectionType } from "@/lib/api/types";

/** `size` tối đa của backend (MenuAccess.MAX_PAGE_SIZE). */
export const MAX_PAGE_SIZE = 100;

// ------------------------------------------------------------------------------------------------ Danh mục

export interface CategoryFilters {
  q: string;
  visible: "" | "true" | "false";
  page: number;
  size: number;
}

export function useCategories(f: CategoryFilters) {
  return useQuery({
    queryKey: ["categories", "list", f],
    queryFn: () => api.get<Page<CategoryDto>>("/categories", cleanParams(f)),
    placeholderData: keepPreviousData,
  });
}

/** Mọi danh mục (dùng cho ô chọn và sắp xếp). */
export function useAllCategories() {
  return useQuery({
    queryKey: ["categories", "all"],
    queryFn: () => api.get<Page<CategoryDto>>("/categories", { page: 0, size: MAX_PAGE_SIZE }),
    select: (p) => p.items,
  });
}

function useMenuMutation<TVars, TData>(fn: (v: TVars) => Promise<TData>, keys: string[]) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      for (const k of keys) void qc.invalidateQueries({ queryKey: [k] });
    },
  });
}

export const useCreateCategory = () =>
  useMenuMutation((v: { name: string; description?: string; displayOrder?: number; visible?: boolean }) => api.post<CategoryDto>("/categories", v), ["categories"]);

export const useUpdateCategory = () =>
  useMenuMutation(
    (v: { id: number; name: string; description?: string; displayOrder: number; version: number }) => {
      const { id, ...body } = v;
      return api.put<CategoryDto>(`/categories/${id}`, body);
    },
    ["categories", "products"],
  );

export const useSetCategoryVisibility = () =>
  useMenuMutation(
    (v: { id: number; visible: boolean; version: number }) => api.patch<CategoryDto>(`/categories/${v.id}/visibility`, { visible: v.visible, version: v.version }),
    ["categories"],
  );

export const useReorderCategories = () =>
  useMenuMutation((items: Array<{ id: number; displayOrder: number; version: number }>) => api.put<CategoryDto[]>("/categories/display-order", { items }), ["categories"]);

// ------------------------------------------------------------------------------------------------ Món

export interface ProductFilters {
  q: string;
  categoryId: string;
  status: ProductStatus | "";
  page: number;
  size: number;
}

export function useProducts(f: ProductFilters) {
  return useQuery({
    queryKey: ["products", "list", f],
    queryFn: () => api.get<Page<ProductDto>>("/products", cleanParams(f)),
    placeholderData: keepPreviousData,
  });
}

export function useProduct(id: number | null) {
  return useQuery({
    queryKey: ["products", "detail", id],
    queryFn: () => api.get<ProductDto>(`/products/${id}`),
    enabled: id !== null,
  });
}

export interface ProductInput {
  categoryId: number;
  name: string;
  description?: string;
  price: number;
  displayOrder?: number;
}

export const useCreateProduct = () =>
  useMenuMutation((v: ProductInput & { status?: ProductStatus }) => api.post<ProductDto>("/products", v), ["products"]);

export const useUpdateProduct = () =>
  useMenuMutation(
    (v: ProductInput & { id: number; displayOrder: number; version: number }) => {
      const { id, ...body } = v;
      return api.put<ProductDto>(`/products/${id}`, body);
    },
    ["products"],
  );

export const useChangeProductStatus = () =>
  useMenuMutation(
    (v: { id: number; status: ProductStatus; version: number; reason?: string }) =>
      api.patch<ProductDto>(`/products/${v.id}/status`, { status: v.status, version: v.version, reason: v.reason || undefined }),
    ["products"],
  );

export const useSetProductOptionGroups = () =>
  useMenuMutation(
    (v: { id: number; optionGroupIds: number[]; version: number }) =>
      api.put<ProductDto>(`/products/${v.id}/option-groups`, { optionGroupIds: v.optionGroupIds, version: v.version }),
    ["products"],
  );

/** Ảnh món: multipart `file`, JPEG/PNG/WebP, tối đa 5MB (backend kiểm tra lại cả chữ ký file). */
export const useUploadProductImage = () =>
  useMenuMutation((v: { id: number; version: number; file: File }) => {
    const form = new FormData();
    form.append("file", v.file);
    return request<ProductDto>({
      method: "POST",
      url: `/products/${v.id}/image`,
      params: { version: v.version },
      data: form,
      headers: { "Content-Type": "multipart/form-data" },
      timeout: 60_000,
    });
  }, ["products"]);

export const useRemoveProductImage = () =>
  useMenuMutation((v: { id: number; version: number }) => api.delete<ProductDto>(`/products/${v.id}/image`, { version: v.version }), ["products"]);

// ------------------------------------------------------------------------------------------------ Nhóm option

export interface OptionGroupFilters {
  q: string;
  active: "" | "true" | "false";
  page: number;
  size: number;
}

export function useOptionGroups(f: OptionGroupFilters) {
  return useQuery({
    queryKey: ["option-groups", "list", f],
    queryFn: () => api.get<Page<OptionGroupDto>>("/option-groups", cleanParams(f)),
    placeholderData: keepPreviousData,
  });
}

export function useAllOptionGroups(enabled = true) {
  return useQuery({
    queryKey: ["option-groups", "all"],
    queryFn: () => api.get<Page<OptionGroupDto>>("/option-groups", { page: 0, size: MAX_PAGE_SIZE }),
    select: (p) => p.items,
    enabled,
  });
}

export interface OptionGroupInput {
  name: string;
  selectionType: SelectionType;
  required: boolean;
  minSelect?: number;
  maxSelect?: number;
  displayOrder?: number;
}

export const useCreateOptionGroup = () =>
  useMenuMutation((v: OptionGroupInput) => api.post<OptionGroupDto>("/option-groups", v), ["option-groups"]);

export const useUpdateOptionGroup = () =>
  useMenuMutation(
    (v: OptionGroupInput & { id: number; displayOrder: number; version: number }) => {
      const { id, ...body } = v;
      return api.put<OptionGroupDto>(`/option-groups/${id}`, body);
    },
    ["option-groups", "products"],
  );

export const useSetOptionGroupActive = () =>
  useMenuMutation(
    (v: { id: number; active: boolean; version: number }) => api.patch<OptionGroupDto>(`/option-groups/${v.id}/active`, { active: v.active, version: v.version }),
    ["option-groups"],
  );

export const useCreateOptionItem = () =>
  useMenuMutation(
    (v: { groupId: number; name: string; extraPrice: number; displayOrder?: number }) => {
      const { groupId, ...body } = v;
      return api.post<OptionItemDto>(`/option-groups/${groupId}/items`, body);
    },
    ["option-groups"],
  );

export const useUpdateOptionItem = () =>
  useMenuMutation(
    (v: { groupId: number; id: number; name: string; extraPrice: number; displayOrder: number; version: number }) => {
      const { groupId, id, ...body } = v;
      return api.put<OptionItemDto>(`/option-groups/${groupId}/items/${id}`, body);
    },
    ["option-groups"],
  );

export const useSetOptionItemActive = () =>
  useMenuMutation(
    (v: { groupId: number; id: number; active: boolean; version: number }) =>
      api.patch<OptionItemDto>(`/option-groups/${v.groupId}/items/${v.id}/active`, { active: v.active, version: v.version }),
    ["option-groups"],
  );
