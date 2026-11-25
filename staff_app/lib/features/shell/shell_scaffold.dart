import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/theme.dart';
import '../../core/providers/core_providers.dart';
import '../../core/realtime/realtime_message.dart';
import '../auth/application/session_controller.dart';
import 'nav_access.dart';

IconData _iconFor(AppTab tab) {
  switch (tab) {
    case AppTab.tables:
      return Icons.table_restaurant_outlined;
    case AppTab.orders:
      return Icons.receipt_long_outlined;
    case AppTab.kitchen:
      return Icons.soup_kitchen_outlined;
    case AppTab.cashier:
      return Icons.payments_outlined;
    case AppTab.account:
      return Icons.person_outline_rounded;
  }
}

/// Trạng thái kết nối realtime (cho chấm tròn ở thanh điều hướng).
final realtimeStatusProvider = StreamProvider<RealtimeStatus>((ref) async* {
  final service = ref.watch(realtimeServiceProvider);
  yield service.status;
  yield* service.statuses;
});

/// Khung điều hướng: `NavigationBar` ở điện thoại dọc, `NavigationRail` ở màn
/// hình rộng (máy tính bảng / ngang). Chỉ hiện tab đúng quyền của vai trò.
class ShellScaffold extends ConsumerWidget {
  const ShellScaffold({super.key, required this.shell});

  /// Thứ tự nhánh trong `StatefulShellRoute` trùng thứ tự [AppTab.values].
  final StatefulNavigationShell shell;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(sessionControllerProvider).user;
    if (user == null) return const SizedBox.shrink();
    final tabs = tabsFor(user);
    final currentTab = AppTab.values[shell.currentIndex];
    var selected = tabs.indexOf(currentTab);
    if (selected < 0) selected = 0;

    void onSelect(int index) {
      final branch = AppTab.values.indexOf(tabs[index]);
      shell.goBranch(branch, initialLocation: branch == shell.currentIndex);
    }

    final wide = MediaQuery.sizeOf(context).width >= 600;
    final status = ref.watch(realtimeStatusProvider).valueOrNull;
    final offline = status == RealtimeStatus.disconnected;

    final body = Column(
      children: <Widget>[
        if (offline)
          Container(
            width: double.infinity,
            color: AppColors.warningSoft,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            child: const Text(
              'Mất kết nối thời gian thực, đang thử lại. Dữ liệu tự làm mới định kỳ.',
              style: TextStyle(color: AppColors.warning, fontSize: 12),
            ),
          ),
        Expanded(child: shell),
      ],
    );

    if (wide) {
      return Scaffold(
        body: Row(
          children: <Widget>[
            NavigationRail(
              selectedIndex: selected,
              onDestinationSelected: onSelect,
              labelType: NavigationRailLabelType.all,
              destinations: <NavigationRailDestination>[
                for (final tab in tabs)
                  NavigationRailDestination(
                    icon: Icon(_iconFor(tab)),
                    label: Text(tab.label),
                  ),
              ],
            ),
            const VerticalDivider(width: 1, thickness: 1),
            Expanded(child: body),
          ],
        ),
      );
    }

    return Scaffold(
      body: body,
      bottomNavigationBar: DecoratedBox(
        decoration: const BoxDecoration(
          border: Border(top: BorderSide(color: AppColors.border)),
        ),
        child: NavigationBar(
          selectedIndex: selected,
          onDestinationSelected: onSelect,
          labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
          destinations: <NavigationDestination>[
            for (final tab in tabs)
              NavigationDestination(
                icon: Icon(_iconFor(tab)),
                label: tab.label,
              ),
          ],
        ),
      ),
    );
  }
}
