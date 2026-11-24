"use client";

import { ACCESS } from "@/lib/access";
import { AccessGuard } from "@/components/layout/AccessGuard";
import { OptionGroupsPage } from "@/features/menu/OptionGroupsPage";

export default function OptionsRoute() {
  return (
    <AccessGuard rule={ACCESS.menu}>
      <OptionGroupsPage />
    </AccessGuard>
  );
}
