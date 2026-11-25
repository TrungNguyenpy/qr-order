import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/providers/core_providers.dart';
import '../domain/auth_models.dart';

enum AuthStatus {
  /// Đang khôi phục phiên khi mở app.
  restoring,

  /// Chưa đăng nhập (hoặc phiên đã hết hạn / bị đăng xuất).
  unauthenticated,

  /// Không khôi phục được phiên do mạng/máy chủ; token vẫn còn, cho phép thử lại.
  restoreFailed,

  authenticated,
}

class AuthState {
  const AuthState({required this.status, this.user, this.notice, this.error});

  const AuthState.restoring() : this(status: AuthStatus.restoring);

  final AuthStatus status;
  final StaffUser? user;

  /// Thông báo hiển thị ở màn đăng nhập (ví dụ "Phiên đăng nhập đã hết hạn").
  final String? notice;

  /// Lỗi khi khôi phục phiên ([AuthStatus.restoreFailed]).
  final ApiException? error;

  bool get isAuthenticated =>
      status == AuthStatus.authenticated && user != null;
}

/// Điều phối phiên đăng nhập: khôi phục khi mở app, đăng nhập, đăng xuất, hết hạn
/// phiên, và bật/tắt STOMP theo trạng thái đăng nhập.
class SessionController extends Notifier<AuthState> {
  @override
  AuthState build() => const AuthState.restoring();

  /// Gọi một lần khi khởi động: nếu có token đã lưu thì xác minh bằng `/auth/me`.
  Future<void> restore() async {
    final tokens = ref.read(tokenStoreProvider);
    await tokens.load();
    if (tokens.current == null) {
      state = const AuthState(status: AuthStatus.unauthenticated);
      return;
    }
    state = const AuthState.restoring();
    try {
      final user = await ref.read(authRepositoryProvider).me();
      if (!user.canUseStaffApp) {
        await _signOutLocally(
          'Tài khoản Quản trị viên không dùng Staff App. Hãy dùng Admin Dashboard.',
        );
        return;
      }
      _setAuthenticated(user);
    } on ApiException catch (error) {
      if (error.isConnectivity || error.isServerError) {
        // Giữ token: có thể chỉ là mất mạng tạm thời (BE-06 mục "không đăng xuất khi lỗi mạng").
        state = AuthState(status: AuthStatus.restoreFailed, error: error);
      } else {
        await _signOutLocally(
          'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
        );
      }
    }
  }

  /// Đăng nhập; ném [ApiException] (hiển thị bằng `userMessage`) khi thất bại.
  Future<void> login(String username, String password) async {
    final user = await ref
        .read(authRepositoryProvider)
        .login(username, password);
    _setAuthenticated(user);
  }

  /// Đăng xuất chủ động.
  Future<void> logout() async {
    ref.read(realtimeServiceProvider).stop();
    try {
      await ref.read(authRepositoryProvider).logout();
    } finally {
      state = const AuthState(status: AuthStatus.unauthenticated);
    }
  }

  /// Gọi từ interceptor khi refresh bị từ chối hoặc tài khoản bị khóa.
  void expire(String reasonCode) {
    if (state.status == AuthStatus.unauthenticated) return;
    final disabled = reasonCode == ApiErrorCodes.accountDisabled;
    ref.read(realtimeServiceProvider).stop();
    unawaited(ref.read(tokenStoreProvider).clear());
    state = AuthState(
      status: AuthStatus.unauthenticated,
      notice: disabled
          ? 'Tài khoản đã bị khóa. Liên hệ quản lý.'
          : 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
    );
  }

  /// Làm mới thông tin/quyền của tài khoản (kéo để làm mới ở màn Tài khoản).
  Future<void> refreshUser() async {
    final user = await ref.read(authRepositoryProvider).me();
    if (state.isAuthenticated) {
      state = AuthState(status: AuthStatus.authenticated, user: user);
    }
  }

  /// Đổi mật khẩu; thành công thì backend thu hồi mọi phiên → quay lại đăng nhập.
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    await ref
        .read(authRepositoryProvider)
        .changePassword(
          currentPassword: currentPassword,
          newPassword: newPassword,
        );
    await _signOutLocally('Đã đổi mật khẩu. Vui lòng đăng nhập lại.');
  }

  void _setAuthenticated(StaffUser user) {
    state = AuthState(status: AuthStatus.authenticated, user: user);
    ref.read(realtimeServiceProvider).start(user.role);
  }

  Future<void> _signOutLocally(String notice) async {
    ref.read(realtimeServiceProvider).stop();
    await ref.read(tokenStoreProvider).clear();
    state = AuthState(status: AuthStatus.unauthenticated, notice: notice);
  }
}

final sessionControllerProvider =
    NotifierProvider<SessionController, AuthState>(SessionController.new);
