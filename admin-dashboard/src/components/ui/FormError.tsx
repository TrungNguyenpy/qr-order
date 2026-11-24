"use client";

import type { ErrorInfo } from "@/lib/errors";
import { Banner } from "./Feedback";
import { Button } from "./Button";

/**
 * Lỗi chung của form. 409 CONCURRENT_MODIFICATION: form đang giữ `version` cũ nên chỉ có cách tải lại.
 * Lỗi theo trường (validation) hiển thị ở từng ô; ở đây chỉ hiện khi không có trường nào khớp.
 */
export function FormError({ info, onReload, hasFieldErrors }: { info: ErrorInfo | null; onReload?: () => void; hasFieldErrors?: boolean }) {
  if (!info) return null;
  if (hasFieldErrors && info.code === "VALIDATION_FAILED") return null;
  return (
    <div className="mb-4">
      <Banner
        tone="danger"
        title={info.conflict ? "Dữ liệu đã thay đổi" : undefined}
        action={
          info.conflict && onReload ? (
            <Button size="sm" onClick={onReload}>
              Tải lại dữ liệu
            </Button>
          ) : undefined
        }
      >
        {info.message}
      </Banner>
    </div>
  );
}
