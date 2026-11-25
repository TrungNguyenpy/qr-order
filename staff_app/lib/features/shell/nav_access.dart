import '../auth/domain/auth_models.dart';

/// Các tab của Staff App. Đợt 1 chỉ làm đầy đủ `tables` và `account`; các tab
/// còn lại là chỗ giữ chỗ cho Đợt 2.
enum AppTab {
  tables('/tables', 'Sơ đồ bàn'),
  orders('/orders', 'Đơn hàng'),
  kitchen('/kitchen', 'Bếp'),
  cashier('/cashier', 'Thu ngân'),
  account('/account', 'Tài khoản');

  const AppTab(this.path, this.label);

  final String path;
  final String label;
}

/// Quyền truy cập tab theo vai trò (khớp backend: `/tables`, `/orders` cho
/// STAFF/MANAGER, bếp cho KITCHEN/MANAGER; thu ngân cần PAYMENT_PROCESS).
bool canAccessTab(AppTab tab, StaffUser user) {
  if (!user.canUseStaffApp) return false;
  switch (tab) {
    case AppTab.tables:
    case AppTab.orders:
      return user.role == UserRole.staff || user.role == UserRole.manager;
    case AppTab.kitchen:
      return user.role == UserRole.kitchen || user.role == UserRole.manager;
    case AppTab.cashier:
      return (user.role == UserRole.staff || user.role == UserRole.manager) &&
          (user.role == UserRole.manager ||
              user.can(Permission.paymentProcess));
    case AppTab.account:
      return true;
  }
}

/// Các tab hiển thị cho [user], theo thứ tự cố định.
List<AppTab> tabsFor(StaffUser user) =>
    AppTab.values.where((tab) => canAccessTab(tab, user)).toList();

/// Màn hình đầu tiên sau đăng nhập.
String homePathFor(StaffUser user) => tabsFor(user).first.path;

/// Đường dẫn [path] có được phép với [user] không (bao gồm đường dẫn con).
bool canAccessPath(String path, StaffUser user) {
  for (final tab in AppTab.values) {
    if (path == tab.path || path.startsWith('${tab.path}/')) {
      return canAccessTab(tab, user);
    }
  }
  return false;
}
