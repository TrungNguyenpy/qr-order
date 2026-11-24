"use client";

import { ACCESS } from "@/lib/access";
import { AccessGuard } from "@/components/layout/AccessGuard";
import { SettingsPage } from "@/features/settings/SettingsPage";

export default function SettingsRoute() {
  return (
    <AccessGuard rule={ACCESS.settings}>
      <SettingsPage />
    </AccessGuard>
  );
}
