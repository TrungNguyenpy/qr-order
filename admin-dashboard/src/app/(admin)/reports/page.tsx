"use client";

import { AccessGuard } from "@/components/layout/AccessGuard";
import { ReportsPage } from "@/features/reports/ReportsPage";

/** MANAGER và ADMIN vào được trang; quyền REPORT_VIEW được kiểm tra trong trang để hiện hướng dẫn cấp quyền. */
export default function ReportsRoute() {
  return (
    <AccessGuard rule={{ roles: ["ADMIN", "MANAGER"] }}>
      <ReportsPage />
    </AccessGuard>
  );
}
