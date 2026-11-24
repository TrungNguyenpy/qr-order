"use client";

import { ACCESS } from "@/lib/access";
import { AccessGuard } from "@/components/layout/AccessGuard";
import { UsersPage } from "@/features/users/UsersPage";

export default function EmployeesPage() {
  return (
    <AccessGuard rule={ACCESS.employees}>
      <UsersPage />
    </AccessGuard>
  );
}
