"use client";

import { AccessGuard } from "@/components/layout/AccessGuard";
import { SessionsPage } from "@/features/sessions/SessionsPage";

export default function SessionsRoute() {
  return (
    <AccessGuard rule={{ roles: ["ADMIN", "MANAGER"] }}>
      <SessionsPage />
    </AccessGuard>
  );
}
