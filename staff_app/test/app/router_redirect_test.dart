import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/app/router.dart';
import 'package:staff_app/features/auth/application/session_controller.dart';
import 'package:staff_app/features/auth/domain/auth_models.dart';

import '../support/fakes.dart';

AuthState _auth(UserRole role) => AuthState(
  status: AuthStatus.authenticated,
  user: fakeUser(role: role),
);

void main() {
  group('resolveRedirect', () {
    test('đang khôi phục -> /startup', () {
      expect(
        resolveRedirect(const AuthState.restoring(), '/tables'),
        equals('/startup'),
      );
      expect(resolveRedirect(const AuthState.restoring(), '/startup'), isNull);
    });

    test('chưa đăng nhập -> /login', () {
      const auth = AuthState(status: AuthStatus.unauthenticated);
      expect(resolveRedirect(auth, '/tables'), equals('/login'));
      expect(resolveRedirect(auth, '/login'), isNull);
    });

    test('đã đăng nhập rời /login và /startup về trang chủ theo vai trò', () {
      expect(
        resolveRedirect(_auth(UserRole.staff), '/login'),
        equals('/tables'),
      );
      expect(
        resolveRedirect(_auth(UserRole.kitchen), '/startup'),
        equals('/kitchen'),
      );
    });

    test(
      'KITCHEN vào /tables bị đưa về /kitchen; STAFF vào /kitchen về /tables',
      () {
        expect(
          resolveRedirect(_auth(UserRole.kitchen), '/tables'),
          equals('/kitchen'),
        );
        expect(
          resolveRedirect(_auth(UserRole.staff), '/kitchen'),
          equals('/tables'),
        );
      },
    );

    test('đổi mật khẩu luôn được phép khi đã đăng nhập', () {
      expect(
        resolveRedirect(_auth(UserRole.kitchen), '/change-password'),
        isNull,
      );
    });
  });
}
