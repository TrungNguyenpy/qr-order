import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api/client";
import { cleanParams } from "@/lib/api/query";
import type { Page, Permission, PermissionGrant, UserDto, UserRole, UserStatus } from "@/lib/api/types";

export interface UserFilters {
  q: string;
  role: UserRole | "";
  status: UserStatus | "";
  page: number;
  size: number;
}

export function useUsers(filters: UserFilters) {
  return useQuery({
    queryKey: ["users", filters],
    queryFn: () => api.get<Page<UserDto>>("/users", cleanParams(filters)),
    placeholderData: keepPreviousData,
  });
}

export function usePermissionGrants(userId: number) {
  return useQuery({
    queryKey: ["user-permissions", userId],
    queryFn: () => api.get<PermissionGrant[]>(`/users/${userId}/permissions`, { includeRevoked: true }),
  });
}

export interface CreateUserInput {
  username: string;
  fullName: string;
  role: UserRole;
  password: string;
  reason?: string;
  currentPassword?: string;
}

/** Mutation dùng chung: sau khi thành công làm mới danh sách user. */
function useUsersMutation<TVars, TData>(fn: (v: TVars) => Promise<TData>, extraKeys: unknown[][] = []) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["users"] });
      for (const k of extraKeys) void qc.invalidateQueries({ queryKey: k });
    },
  });
}

export const useCreateUser = () => useUsersMutation((v: CreateUserInput) => api.post<UserDto>("/users", v));

export const useUpdateUser = () =>
  useUsersMutation((v: { id: number; fullName: string; version: number }) =>
    api.put<UserDto>(`/users/${v.id}`, { fullName: v.fullName, version: v.version }),
  );

export const useChangeUserStatus = () =>
  useUsersMutation((v: { id: number; status: UserStatus; reason?: string }) =>
    api.patch<UserDto>(`/users/${v.id}/status`, { status: v.status, reason: v.reason || undefined }),
  );

export const useChangeUserRole = () =>
  useUsersMutation((v: { id: number; role: UserRole; reason: string; currentPassword: string }) =>
    api.patch<UserDto>(`/users/${v.id}/role`, { role: v.role, reason: v.reason, currentPassword: v.currentPassword }),
  );

export const useResetPassword = () =>
  useUsersMutation((v: { id: number; newPassword: string; reason?: string }) =>
    api.post<UserDto>(`/users/${v.id}/reset-password`, { newPassword: v.newPassword, reason: v.reason || undefined }),
  );

export const useGrantPermission = (userId: number) =>
  useUsersMutation(
    (v: { permission: Permission; reason: string; currentPassword?: string }) =>
      api.post<PermissionGrant>(`/users/${userId}/permissions`, {
        permission: v.permission,
        reason: v.reason,
        currentPassword: v.currentPassword || undefined,
      }),
    [["user-permissions", userId]],
  );

export const useRevokePermission = (userId: number) =>
  useUsersMutation(
    (v: { permission: Permission; reason: string; currentPassword?: string }) =>
      api.post<PermissionGrant>(`/users/${userId}/permissions/${v.permission}/revoke`, {
        reason: v.reason,
        currentPassword: v.currentPassword || undefined,
      }),
    [["user-permissions", userId]],
  );
