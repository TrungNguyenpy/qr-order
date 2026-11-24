"use client";

import { AccessGuard } from "@/components/layout/AccessGuard";
import { OperationsPage } from "@/features/operations/OperationsPage";

export default function OperationsRoute() {
  return (
    <AccessGuard rule={{ roles: ["ADMIN", "MANAGER"] }}>
      <OperationsPage />
    </AccessGuard>
  );
}
