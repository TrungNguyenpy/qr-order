"use client";

import type { ReactNode } from "react";
import { canAccess, type AccessRule } from "@/lib/access";
import { useAuthStore } from "@/stores/auth";
import { EmptyState } from "@/components/ui/Feedback";

/** Chặn màn hình khi role/permission không đủ (backend vẫn kiểm tra lại, đây chỉ là lớp UX). */
export function AccessGuard({ rule, children }: { rule: AccessRule; children: ReactNode }) {
  const user = useAuthStore((s) => s.user);
  if (!canAccess(user, rule)) {
    return (
      <EmptyState
        title="Bạn không có quyền truy cập chức năng này"
        description={
          rule.permission
            ? `Cần quyền ${rule.permission}. Liên hệ ADMIN để được cấp.`
            : `Chức năng dành cho: ${(rule.roles ?? []).join(", ")}.`
        }
      />
    );
  }
  return <>{children}</>;
}
