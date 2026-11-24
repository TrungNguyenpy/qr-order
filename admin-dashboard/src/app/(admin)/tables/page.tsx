"use client";

import { ACCESS } from "@/lib/access";
import { AccessGuard } from "@/components/layout/AccessGuard";
import { TablesPage } from "@/features/tables/TablesPage";

export default function TablesRoute() {
  return (
    <AccessGuard rule={ACCESS.tablesView}>
      <TablesPage />
    </AccessGuard>
  );
}
