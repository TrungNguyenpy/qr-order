"use client";

import { ACCESS } from "@/lib/access";
import { AccessGuard } from "@/components/layout/AccessGuard";
import { CategoriesPage } from "@/features/menu/CategoriesPage";

export default function CategoriesRoute() {
  return (
    <AccessGuard rule={ACCESS.menu}>
      <CategoriesPage />
    </AccessGuard>
  );
}
