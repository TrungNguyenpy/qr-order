"use client";

import { ACCESS } from "@/lib/access";
import { AccessGuard } from "@/components/layout/AccessGuard";
import { ProductsPage } from "@/features/menu/ProductsPage";

export default function ProductsRoute() {
  return (
    <AccessGuard rule={ACCESS.menu}>
      <ProductsPage />
    </AccessGuard>
  );
}
