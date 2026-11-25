import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/auth/application/session_controller.dart';
import '../features/auth/presentation/account_screen.dart';
import '../features/auth/presentation/change_password_screen.dart';
import '../features/auth/presentation/login_screen.dart';
import '../features/auth/presentation/startup_screen.dart';
import '../features/billing/presentation/cashier_screen.dart';
import '../features/kitchen/presentation/kitchen_screen.dart';
import '../features/orders/presentation/orders_screen.dart';
import '../features/shell/nav_access.dart';
import '../features/shell/shell_scaffold.dart';
import '../features/tables/presentation/floor_plan_screen.dart';

const String startupPath = '/startup';
const String loginPath = '/login';
const String changePasswordPath = '/change-password';

/// Quyết định chuyển hướng theo trạng thái đăng nhập và vai trò (thuần, dễ kiểm thử).
String? resolveRedirect(AuthState auth, String location) {
  switch (auth.status) {
    case AuthStatus.restoring:
    case AuthStatus.restoreFailed:
      return location == startupPath ? null : startupPath;
    case AuthStatus.unauthenticated:
      return location == loginPath ? null : loginPath;
    case AuthStatus.authenticated:
      final user = auth.user;
      if (user == null) return loginPath;
      final home = homePathFor(user);
      if (location == startupPath || location == loginPath || location == '/') {
        return home;
      }
      if (location == changePasswordPath) return null;
      return canAccessPath(location, user) ? null : home;
  }
}

/// Cầu nối để GoRouter tính lại redirect mỗi khi trạng thái đăng nhập đổi.
class _AuthRefresh extends ChangeNotifier {
  void ping() => notifyListeners();
}

final routerProvider = Provider<GoRouter>((ref) {
  final refresh = _AuthRefresh();
  ref.listen<AuthState>(sessionControllerProvider, (_, __) => refresh.ping());
  ref.onDispose(refresh.dispose);

  final router = GoRouter(
    initialLocation: startupPath,
    refreshListenable: refresh,
    redirect: (context, state) =>
        resolveRedirect(ref.read(sessionControllerProvider), state.uri.path),
    routes: <RouteBase>[
      GoRoute(path: startupPath, builder: (_, __) => const StartupScreen()),
      GoRoute(path: loginPath, builder: (_, __) => const LoginScreen()),
      GoRoute(
        path: changePasswordPath,
        builder: (_, __) => const ChangePasswordScreen(),
      ),
      // Thứ tự nhánh PHẢI trùng AppTab.values (ShellScaffold dựa vào đó).
      StatefulShellRoute.indexedStack(
        builder: (context, state, shell) => ShellScaffold(shell: shell),
        branches: <StatefulShellBranch>[
          StatefulShellBranch(
            routes: <RouteBase>[
              GoRoute(
                path: AppTab.tables.path,
                builder: (_, __) => const FloorPlanScreen(),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: <RouteBase>[
              GoRoute(
                path: AppTab.orders.path,
                builder: (_, __) => const OrdersScreen(),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: <RouteBase>[
              GoRoute(
                path: AppTab.kitchen.path,
                builder: (_, __) => const KitchenScreen(),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: <RouteBase>[
              GoRoute(
                path: AppTab.cashier.path,
                builder: (_, __) => const CashierScreen(),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: <RouteBase>[
              GoRoute(
                path: AppTab.account.path,
                builder: (_, __) => const AccountScreen(),
              ),
            ],
          ),
        ],
      ),
    ],
  );
  ref.onDispose(router.dispose);
  return router;
});
