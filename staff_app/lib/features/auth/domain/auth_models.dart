import '../../../core/storage/token_store.dart';
import '../../../core/util/json_utils.dart';

/// Vai trò tài khoản (backend `UserRole`).
enum UserRole {
  admin('ADMIN', 'Quản trị viên'),
  manager('MANAGER', 'Quản lý'),
  staff('STAFF', 'Nhân viên phục vụ'),
  kitchen('KITCHEN', 'Nhân viên bếp');

  const UserRole(this.apiName, this.label);

  final String apiName;
  final String label;

  static UserRole? parse(Object? value) {
    final text = asStringOrNull(value);
    for (final role in UserRole.values) {
      if (role.apiName == text) return role;
    }
    return null;
  }
}

/// 8 permission nghiệp vụ (backend `Permission`, SRS §10). MANAGER có cả 8;
/// role khác chỉ có khi được cấp.
enum Permission {
  paymentProcess('PAYMENT_PROCESS', 'Thu tiền, tính tiền'),
  billDiscount('BILL_DISCOUNT', 'Áp dụng giảm giá'),
  billVoid('BILL_VOID', 'Void hóa đơn'),
  refundRecord('REFUND_RECORD', 'Ghi nhận hoàn tiền'),
  itemCancelApprove('ITEM_CANCEL_APPROVE', 'Duyệt yêu cầu hủy món'),
  sessionForceClose('SESSION_FORCE_CLOSE', 'Đóng cưỡng bức phiên'),
  sessionReopen('SESSION_REOPEN', 'Mở lại phiên (Manager)'),
  reportView('REPORT_VIEW', 'Xem báo cáo');

  const Permission(this.apiName, this.label);

  final String apiName;
  final String label;

  static Permission? parse(Object? value) {
    final text = asStringOrNull(value);
    for (final permission in Permission.values) {
      if (permission.apiName == text) return permission;
    }
    return null;
  }
}

/// Người dùng hiện tại (backend `CurrentUserResponse`, `GET /api/v1/auth/me`).
class StaffUser {
  const StaffUser({
    required this.id,
    required this.username,
    required this.fullName,
    required this.role,
    required this.status,
    required this.permissions,
    this.lastLoginAt,
    this.passwordChangedAt,
  });

  /// Ném [FormatException] nếu thiếu `id`/`username` hoặc role không hỗ trợ.
  factory StaffUser.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    final role = UserRole.parse(json['role']);
    if (id == null || role == null) {
      throw const FormatException('Thông tin tài khoản không hợp lệ.');
    }
    final permissions = <Permission>{};
    final rawPermissions = json['permissions'];
    if (rawPermissions is List) {
      for (final item in rawPermissions) {
        final permission = Permission.parse(item);
        if (permission != null) permissions.add(permission);
      }
    }
    return StaffUser(
      id: id,
      username: asString(json['username']),
      fullName: asString(json['fullName']),
      role: role,
      status: asString(json['status'], fallback: 'ACTIVE'),
      permissions: permissions,
      lastLoginAt: asDateTime(json['lastLoginAt']),
      passwordChangedAt: asDateTime(json['passwordChangedAt']),
    );
  }

  final int id;
  final String username;
  final String fullName;
  final UserRole role;
  final String status;
  final Set<Permission> permissions;
  final DateTime? lastLoginAt;
  final DateTime? passwordChangedAt;

  bool can(Permission permission) => permissions.contains(permission);

  /// Có quyền nghiệp vụ [permission]: MANAGER mặc định có đủ 8 permission,
  /// vai trò khác chỉ có khi được cấp.
  bool has(Permission permission) =>
      role == UserRole.manager || permissions.contains(permission);

  /// Tên hiển thị: họ tên, nếu trống thì tên đăng nhập.
  String get displayName => fullName.trim().isEmpty ? username : fullName;

  /// Staff App dành cho STAFF, KITCHEN, MANAGER. ADMIN không kế thừa quyền
  /// nghiệp vụ (BR-A06) nên dùng Admin Dashboard.
  bool get canUseStaffApp => role != UserRole.admin;
}

/// Kết quả đăng nhập (backend `TokenResponse`).
class LoginResult {
  const LoginResult({required this.tokens, required this.user});

  /// Ném [FormatException] khi thiếu token hoặc thông tin tài khoản.
  factory LoginResult.fromJson(Map<String, dynamic> json) {
    final tokens = TokenPair.fromJson(json);
    if (!tokens.isUsable) {
      throw const FormatException('Phản hồi đăng nhập thiếu token.');
    }
    return LoginResult(
      tokens: tokens,
      user: StaffUser.fromJson(asMap(json['user'])),
    );
  }

  final TokenPair tokens;
  final StaffUser user;
}
