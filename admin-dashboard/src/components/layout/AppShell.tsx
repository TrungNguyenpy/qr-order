"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { authApi } from "@/lib/api/auth";
import { canAccess, canUseDashboard } from "@/lib/access";
import { cn } from "@/lib/cn";
import { useAuthHydrated } from "@/hooks/useHydrated";
import { useAuthStore } from "@/stores/auth";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Feedback";
import { ChangePasswordModal } from "@/features/auth/ChangePasswordModal";
import { NAV } from "./nav";

const ROLE_LABEL = { ADMIN: "Quản trị viên", MANAGER: "Quản lý", STAFF: "Nhân viên", KITCHEN: "Bếp" } as const;

function NavIcon({ name }: { name: "table" | "menu" | "users" | "settings" | "report" | "session" | "ops" }) {
  const p = { width: 16, height: 16, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };
  switch (name) {
    case "table":
      return (
        <svg {...p} aria-hidden="true">
          <rect x="3" y="4" width="7" height="7" rx="1" />
          <rect x="14" y="4" width="7" height="7" rx="1" />
          <rect x="3" y="14" width="7" height="7" rx="1" />
          <rect x="14" y="14" width="7" height="7" rx="1" />
        </svg>
      );
    case "report":
      return (
        <svg {...p} aria-hidden="true">
          <path d="M4 20V4M4 20h16M8 16v-5M12 16V8M16 16v-3" />
        </svg>
      );
    case "session":
      return (
        <svg {...p} aria-hidden="true">
          <rect x="4" y="3" width="16" height="18" rx="2" />
          <path d="M8 8h8M8 12h8M8 16h5" />
        </svg>
      );
    case "ops":
      return (
        <svg {...p} aria-hidden="true">
          <path d="M12 3 3 19h18L12 3zM12 10v4M12 17v.5" />
        </svg>
      );
    case "menu":
      return (
        <svg {...p} aria-hidden="true">
          <path d="M4 6h16M4 12h16M4 18h10" />
        </svg>
      );
    case "users":
      return (
        <svg {...p} aria-hidden="true">
          <circle cx="9" cy="8" r="3.2" />
          <path d="M3 20c0-3.3 2.7-5.5 6-5.5s6 2.2 6 5.5M16 5.5a3 3 0 0 1 0 5.8M18 14.8c1.8.6 3 2.2 3 5.2" />
        </svg>
      );
    default:
      return (
        <svg {...p} aria-hidden="true">
          <circle cx="12" cy="12" r="3" />
          <path d="M12 3v2.5M12 18.5V21M3 12h2.5M18.5 12H21M5.6 5.6l1.8 1.8M16.6 16.6l1.8 1.8M18.4 5.6l-1.8 1.8M7.4 16.6l-1.8 1.8" />
        </svg>
      );
  }
}

/**
 * Khung ứng dụng: bảo vệ route (chưa đăng nhập -> /login), đồng bộ user từ /auth/me (role/permission/khóa
 * tài khoản có hiệu lực ngay, BR-A04), sidebar theo quyền, menu người dùng.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const qc = useQueryClient();
  const hydrated = useAuthHydrated();
  const token = useAuthStore((s) => s.accessToken);
  const user = useAuthStore((s) => s.user);
  // Ngăn kéo mobile gắn với đường dẫn lúc mở: đổi trang thì tự đóng, không cần setState trong effect.
  const [drawerPath, setDrawerPath] = useState<string | null>(null);
  const drawer = drawerPath === pathname;
  const setDrawer = (open: boolean) => setDrawerPath(open ? pathname : null);
  const [pwOpen, setPwOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    if (hydrated && !token) router.replace(`/login?next=${encodeURIComponent(pathname)}`);
  }, [hydrated, token, router, pathname]);


  const me = useQuery({
    queryKey: ["me"],
    queryFn: authApi.me,
    enabled: hydrated && !!token,
    staleTime: 60_000,
    refetchInterval: 5 * 60_000,
  });
  useEffect(() => {
    if (me.data) useAuthStore.getState().setUser(me.data);
  }, [me.data]);

  async function logout() {
    const refresh = useAuthStore.getState().refreshToken;
    if (refresh) await authApi.logout(refresh).catch(() => undefined);
    useAuthStore.getState().clear(null);
    qc.clear();
    router.replace("/login");
  }

  if (!hydrated || !token || !user) {
    return (
      <div className="flex min-h-dvh" role="status" aria-label="Đang tải">
        <div className="hidden w-60 border-r border-line bg-surface p-4 md:block">
          <Skeleton className="mb-6 h-6 w-28" />
          {Array.from({ length: 5 }, (_, i) => (
            <Skeleton key={i} className="mb-3 h-8 w-full" />
          ))}
        </div>
        <div className="flex-1 p-8">
          <Skeleton className="mb-6 h-7 w-56" />
          <Skeleton className="h-64 w-full" />
        </div>
      </div>
    );
  }

  if (!canUseDashboard(user)) {
    return (
      <main className="flex min-h-dvh flex-col items-center justify-center gap-4 px-4 text-center">
        <h1 className="text-lg font-semibold">Tài khoản không có quyền dùng Admin Dashboard</h1>
        <p className="max-w-md text-ink-2">Chỉ tài khoản ADMIN và MANAGER đang hoạt động mới vào được. STAFF và KITCHEN dùng Staff App.</p>
        <Button variant="primary" onClick={() => void logout()}>
          Đăng xuất
        </Button>
      </main>
    );
  }

  const items = NAV.filter((n) => canAccess(user, n.rule));

  const sidebar = (
    <nav aria-label="Điều hướng chính" className="flex h-full flex-col">
      <div className="flex h-14 items-center gap-2 px-4">
        <span className="flex h-7 w-7 items-center justify-center rounded-ctl bg-ink text-[11px] font-semibold text-white">QR</span>
        <span className="font-semibold tracking-tight">QR Order</span>
      </div>
      <ul className="flex-1 space-y-0.5 px-2 py-2">
        {items.map((n) => {
          const active = pathname === n.href || pathname.startsWith(`${n.href}/`);
          return (
            <li key={n.href}>
              <Link
                href={n.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "flex items-center gap-2.5 rounded-ctl px-3 py-2 text-[13.5px] transition-colors",
                  active ? "bg-surface-2 font-medium text-ink" : "text-ink-2 hover:bg-surface-2 hover:text-ink",
                )}
              >
                <NavIcon name={n.icon} />
                {n.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );

  return (
    <div className="flex min-h-dvh">
      <aside className="sticky top-0 hidden h-dvh w-60 shrink-0 border-r border-line bg-surface md:block">{sidebar}</aside>

      {drawer && (
        <div className="fixed inset-0 z-40 md:hidden">
          <div className="absolute inset-0 bg-black/40" onClick={() => setDrawer(false)} aria-hidden="true" />
          <aside className="relative h-full w-64 border-r border-line bg-surface">{sidebar}</aside>
        </div>
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex h-14 items-center justify-between gap-3 border-b border-line bg-bg/95 px-4 backdrop-blur-sm md:px-8">
          <button
            type="button"
            className="rounded-ctl p-1.5 text-ink-2 hover:bg-surface-2 md:hidden"
            aria-label="Mở menu"
            onClick={() => setDrawer(true)}
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </button>
          <div className="flex-1" />
          <div className="relative">
            <button
              type="button"
              aria-haspopup="menu"
              aria-expanded={menuOpen}
              onClick={() => setMenuOpen((v) => !v)}
              className="flex items-center gap-2 rounded-ctl px-2 py-1.5 hover:bg-surface-2"
            >
              <span className="flex h-7 w-7 items-center justify-center rounded-full bg-accent-soft text-xs font-semibold text-accent">
                {user.fullName.trim().charAt(0).toUpperCase() || "?"}
              </span>
              <span className="hidden text-left leading-tight sm:block">
                <span className="block text-[13px] font-medium">{user.fullName}</span>
                <span className="block text-xs text-ink-3">{ROLE_LABEL[user.role]}</span>
              </span>
            </button>
            {menuOpen && (
              <>
                <div className="fixed inset-0 z-40" onClick={() => setMenuOpen(false)} aria-hidden="true" />
                <div role="menu" className="absolute right-0 z-50 mt-1 w-48 rounded-card border border-line bg-bg py-1 shadow-sm">
                  <button
                    role="menuitem"
                    type="button"
                    className="block w-full px-3 py-2 text-left text-[13px] hover:bg-surface"
                    onClick={() => {
                      setMenuOpen(false);
                      setPwOpen(true);
                    }}
                  >
                    Đổi mật khẩu
                  </button>
                  <button
                    role="menuitem"
                    type="button"
                    className="block w-full px-3 py-2 text-left text-[13px] text-danger hover:bg-surface"
                    onClick={() => void logout()}
                  >
                    Đăng xuất
                  </button>
                </div>
              </>
            )}
          </div>
        </header>
        <main className="min-w-0 flex-1 px-4 py-6 md:px-8">
          <div className="mx-auto w-full max-w-6xl">{children}</div>
        </main>
      </div>

      {pwOpen && <ChangePasswordModal onClose={() => setPwOpen(false)} onChanged={() => void logout()} />}
    </div>
  );
}
