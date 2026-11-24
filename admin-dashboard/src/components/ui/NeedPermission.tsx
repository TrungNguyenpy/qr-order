"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { Banner } from "./Feedback";

/**
 * Thông báo rõ ràng khi thiếu quyền (thay cho lỗi 403 chung chung).
 * ADMIN không mặc định kế thừa quyền MANAGER (BR-A06): cần tự cấp ở mục Nhân sự rồi đăng nhập lại (AD-03).
 */
export function NeedPermission({ permission, isAdmin, children }: { permission: string; isAdmin: boolean; children?: ReactNode }) {
  return (
    <Banner tone="warn" title={`Tài khoản chưa có quyền ${permission}`}>
      <p>{children}</p>
      {isAdmin ? (
        <p className="mt-2">
          Với tài khoản ADMIN: vào <Link href="/employees" className="font-medium text-accent underline">Nhân sự → Phân quyền</Link>, tự cấp
          quyền <b>{permission}</b> cho mình (cần nhập lại mật khẩu). Sau khi cấp, hệ thống sẽ yêu cầu đăng nhập lại để quyền có hiệu lực.
        </p>
      ) : (
        <p className="mt-2">Liên hệ ADMIN để được cấp quyền {permission}.</p>
      )}
    </Banner>
  );
}
