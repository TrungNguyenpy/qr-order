import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/network/api_exception.dart';
import 'package:staff_app/core/providers/core_providers.dart';
import 'package:staff_app/core/storage/server_url_store.dart';
import 'package:staff_app/core/storage/token_store.dart';
import 'package:staff_app/features/auth/application/session_controller.dart';
import 'package:staff_app/features/auth/domain/auth_models.dart';

import '../support/fakes.dart';

void main() {
  late FakeAuthRepository repository;
  late FakeRealtimeService realtime;
  late InMemoryTokenStore tokens;
  late ProviderContainer container;

  ProviderContainer build({TokenPair? saved}) {
    repository = FakeAuthRepository();
    realtime = FakeRealtimeService();
    tokens = InMemoryTokenStore(saved);
    return ProviderContainer(
      overrides: [
        serverUrlStoreProvider.overrideWithValue(
          InMemoryServerUrlStore('http://test'),
        ),
        tokenStoreProvider.overrideWithValue(tokens),
        authRepositoryProvider.overrideWithValue(repository),
        realtimeServiceProvider.overrideWithValue(realtime),
      ],
    );
  }

  const saved = TokenPair(accessToken: 'a', refreshToken: 'r');

  tearDown(() => container.dispose());

  SessionController controller() =>
      container.read(sessionControllerProvider.notifier);

  AuthState state() => container.read(sessionControllerProvider);

  test('không có token đã lưu -> chưa đăng nhập', () async {
    container = build();
    await controller().restore();
    expect(state().status, equals(AuthStatus.unauthenticated));
    expect(realtime.started, isEmpty);
  });

  test(
    'có token và /auth/me thành công -> đăng nhập, bật STOMP đúng vai trò',
    () async {
      container = build(saved: saved);
      repository.meResult = fakeUser(role: UserRole.kitchen);

      await controller().restore();

      expect(state().isAuthenticated, isTrue);
      expect(state().user!.role, equals(UserRole.kitchen));
      expect(realtime.started, equals(<UserRole>[UserRole.kitchen]));
    },
  );

  test('mất mạng khi khôi phục -> restoreFailed, VẪN giữ token', () async {
    container = build(saved: saved);
    repository.meError = ApiException.network();

    await controller().restore();

    expect(state().status, equals(AuthStatus.restoreFailed));
    expect(tokens.current, isNotNull);

    repository.meError = null;
    await controller().restore();
    expect(state().isAuthenticated, isTrue);
  });

  test('401 khi khôi phục -> về màn đăng nhập và xóa token', () async {
    container = build(saved: saved);
    repository.meError = const ApiException(
      code: ApiErrorCodes.tokenExpired,
      message: 'x',
      statusCode: 401,
    );

    await controller().restore();

    expect(state().status, equals(AuthStatus.unauthenticated));
    expect(tokens.current, isNull);
    expect(state().notice, isNotNull);
  });

  test('tài khoản ADMIN bị từ chối ở Staff App', () async {
    container = build(saved: saved);
    repository.meResult = fakeUser(role: UserRole.admin);

    await controller().restore();

    expect(state().status, equals(AuthStatus.unauthenticated));
    expect(realtime.started, isEmpty);
  });

  test(
    'login thành công bật STOMP; login lỗi ném ApiException và giữ trạng thái',
    () async {
      container = build();
      await controller().restore();

      repository.loginError = const ApiException(
        code: ApiErrorCodes.invalidCredentials,
        message: 'Sai tên đăng nhập hoặc mật khẩu',
        statusCode: 401,
      );
      await expectLater(
        controller().login('staff1', 'sai'),
        throwsA(isA<ApiException>()),
      );
      expect(state().status, equals(AuthStatus.unauthenticated));

      repository.loginError = null;
      repository.loginResult = fakeUser(role: UserRole.manager);
      await controller().login('staff1', 'Dung1234');
      expect(state().isAuthenticated, isTrue);
      expect(realtime.started, equals(<UserRole>[UserRole.manager]));
    },
  );

  test(
    'phiên hết hạn (interceptor báo) -> dừng STOMP, xóa token, báo người dùng',
    () async {
      container = build(saved: saved);
      await controller().restore();

      controller().expire(ApiErrorCodes.refreshTokenReused);

      expect(state().status, equals(AuthStatus.unauthenticated));
      expect(state().notice, contains('hết hạn'));
      expect(realtime.stopCalls, greaterThanOrEqualTo(1));
      expect(tokens.current, isNull);
    },
  );

  test('tài khoản bị khóa -> thông báo riêng', () async {
    container = build(saved: saved);
    await controller().restore();

    controller().expire(ApiErrorCodes.accountDisabled);

    expect(state().notice, contains('khóa'));
  });

  test('đăng xuất gọi repository và dừng STOMP', () async {
    container = build(saved: saved);
    await controller().restore();

    await controller().logout();

    expect(repository.logoutCalls, equals(1));
    expect(realtime.stopCalls, greaterThanOrEqualTo(1));
    expect(state().status, equals(AuthStatus.unauthenticated));
  });

  test('đổi mật khẩu thành công -> đăng xuất cục bộ với thông báo', () async {
    container = build(saved: saved);
    await controller().restore();

    await controller().changePassword(
      currentPassword: 'Cu12345678',
      newPassword: 'Moi12345678',
    );

    expect(repository.changePasswordCalls, equals(1));
    expect(state().status, equals(AuthStatus.unauthenticated));
    expect(state().notice, contains('đổi mật khẩu'));
    expect(tokens.current, isNull);
  });

  test(
    'đổi mật khẩu sai mật khẩu hiện tại -> vẫn đăng nhập, ném lỗi',
    () async {
      container = build(saved: saved);
      await controller().restore();
      repository.changePasswordError = const ApiException(
        code: ApiErrorCodes.reauthenticationFailed,
        message: 'Mật khẩu hiện tại không đúng',
        statusCode: 403,
      );

      await expectLater(
        controller().changePassword(
          currentPassword: 'sai',
          newPassword: 'Moi12345678',
        ),
        throwsA(isA<ApiException>()),
      );
      expect(state().isAuthenticated, isTrue);
    },
  );
}
