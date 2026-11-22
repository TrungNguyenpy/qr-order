"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { useSessionRuntime } from "@/hooks/useSessionRuntime";
import { selectCartCount, useCartStore } from "@/stores/cart";
import { useSessionHydrated, useSessionStore } from "@/stores/session";
import { Banner, Skeleton } from "@/components/ui/primitives";

const TABS = [
  { href: "/menu", label: "Thực đơn" },
  { href: "/cart", label: "Giỏ hàng" },
  { href: "/orders", label: "Đơn của bàn" },
  { href: "/service", label: "Phục vụ" },
] as const;

export default function AppLayout({ children }: { children: ReactNode }) {
  const hydrated = useSessionHydrated();
  const phase = useSessionStore((s) => s.phase);
  const tableName = useSessionStore((s) => s.tableName);
  const canOrder = useSessionStore((s) => s.canOrder);
  const pinRequired = useSessionStore((s) => s.pinRequired);
  const qrToken = useSessionStore((s) => s.qrToken);
  const sessionId = useSessionStore((s) => s.sessionId);
  const cartCount = useCartStore(selectCartCount);
  const pathname = usePathname();
  const rt = useSessionRuntime();

  if (!hydrated) {
    return (
      <div className="mx-auto max-w-lg p-4">
        <Skeleton className="h-6 w-32" />
      </div>
    );
  }

  if (phase === "closed") {
    return (
      <Gate
        title="Phiên của bàn đã kết thúc"
        text="Cảm ơn bạn đã dùng bữa. Nếu muốn gọi thêm, hãy quét lại mã QR trên bàn."
        href={qrToken ? `/t/${qrToken}` : undefined}
        cta="Vào lại bàn"
      />
    );
  }
  // Phiên lưu cũ/thiếu sessionId: bắt vào bàn lại để lấy đủ thông tin.
  if (phase === "active" && !sessionId) {
    return <Gate title="Cần vào lại bàn" text="Thông tin phiên đã cũ. Vui lòng vào lại bàn để tiếp tục." href={qrToken ? `/t/${qrToken}` : undefined} cta="Vào lại bàn" />;
  }
  if (phase !== "active") {
    return <Gate title="Chưa vào bàn" text="Hãy quét mã QR trên bàn bằng camera điện thoại để bắt đầu gọi món." />;
  }
  if (pinRequired && qrToken) {
    return <Gate title="Cần nhập mã PIN" text="Bàn này yêu cầu mã PIN trước khi gọi món." href={`/t/${qrToken}`} cta="Nhập PIN" />;
  }

  return (
    <div className="mx-auto flex min-h-dvh max-w-lg flex-col">
      <header className="sticky top-0 z-20 border-b border-line bg-bg">
        <div className="flex h-12 items-center justify-between px-4">
          <span className="text-[15px] font-semibold">{tableName || "QR Order"}</span>
          <span className="flex items-center gap-1.5 text-xs text-ink-3" aria-live="polite">
            <span className={`size-2 rounded-full ${rt === "connected" ? "bg-ok" : "bg-warn"}`} aria-hidden />
            {rt === "connected" ? "Trực tuyến" : "Đang kết nối lại"}
          </span>
        </div>
        {!canOrder ? <Banner tone="warn">Bàn đang chờ thanh toán — tạm dừng gọi món.</Banner> : null}
      </header>

      <div className="flex-1 pb-20">{children}</div>

      <nav aria-label="Điều hướng" className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-bg pb-[env(safe-area-inset-bottom)]">
        <ul className="mx-auto grid max-w-lg grid-cols-4">
          {TABS.map((t) => {
            const active = pathname.startsWith(t.href);
            return (
              <li key={t.href}>
                <Link
                  href={t.href}
                  aria-current={active ? "page" : undefined}
                  className={`relative flex h-14 flex-col items-center justify-center text-xs font-medium ${active ? "text-accent" : "text-ink-2"}`}
                >
                  {t.label}
                  {t.href === "/cart" && cartCount > 0 ? (
                    <span className="absolute right-[22%] top-2 min-w-4 rounded-full bg-accent px-1 text-center text-[10px] leading-4 text-accent-ink">{cartCount}</span>
                  ) : null}
                  {active ? <span className="absolute inset-x-6 top-0 h-0.5 bg-accent" aria-hidden /> : null}
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>
    </div>
  );
}

function Gate({ title, text, href, cta }: { title: string; text: string; href?: string; cta?: string }) {
  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col justify-center px-5 py-10">
      <h1 className="text-xl font-semibold">{title}</h1>
      <p className="mt-2 text-[15px] text-ink-2">{text}</p>
      {href && cta ? (
        <Link href={href} className="mt-5 inline-flex h-11 items-center justify-center rounded-ctl bg-accent px-4 text-[15px] font-medium text-accent-ink">
          {cta}
        </Link>
      ) : null}
    </main>
  );
}
