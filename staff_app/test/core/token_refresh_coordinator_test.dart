import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/network/api_exception.dart';
import 'package:staff_app/core/network/token_refresh_coordinator.dart';
import 'package:staff_app/core/storage/token_store.dart';

TokenPair _pair(String access, String refresh) =>
    TokenPair(accessToken: access, refreshToken: refresh);

void main() {
  group('TokenRefreshCoordinator (BE-06)', () {
    test(
      '20 yêu cầu 401 đồng thời chỉ tạo đúng 1 lần gọi /auth/refresh',
      () async {
        final store = InMemoryTokenStore(_pair('old', 'r1'));
        final gate = Completer<void>();
        final usedRefreshTokens = <String>[];

        final coordinator = TokenRefreshCoordinator(
          store: store,
          refreshCall: (refreshToken) async {
            usedRefreshTokens.add(refreshToken);
            await gate.future;
            return _pair('new', 'r2');
          },
        );

        final calls = List<Future<TokenPair>>.generate(
          20,
          (_) => coordinator.refresh(staleAccessToken: 'old'),
        );
        gate.complete();
        final results = await Future.wait(calls);

        expect(coordinator.refreshCalls, equals(1));
        expect(usedRefreshTokens, equals(<String>['r1']));
        expect(results.every((pair) => pair.accessToken == 'new'), isTrue);
        expect(store.current!.refreshToken, equals('r2'));
      },
    );

    test(
      'yêu cầu đến muộn mang token cũ dùng lại token mới, không refresh thêm',
      () async {
        final store = InMemoryTokenStore(_pair('old', 'r1'));
        final coordinator = TokenRefreshCoordinator(
          store: store,
          refreshCall: (_) async => _pair('new', 'r2'),
        );

        await coordinator.refresh(staleAccessToken: 'old');
        final late = await coordinator.refresh(staleAccessToken: 'old');

        expect(coordinator.refreshCalls, equals(1));
        expect(late.accessToken, equals('new'));
      },
    );

    test('refresh bị từ chối 401 -> xóa token và báo phiên hết hạn', () async {
      final store = InMemoryTokenStore(_pair('old', 'r1'));
      final coordinator = TokenRefreshCoordinator(
        store: store,
        refreshCall: (_) async => throw const ApiException(
          code: ApiErrorCodes.refreshTokenReused,
          message: 'Phiên bị thu hồi',
          statusCode: 401,
        ),
      );

      await expectLater(
        coordinator.refresh(staleAccessToken: 'old'),
        throwsA(isA<SessionExpiredException>()),
      );
      expect(store.current, isNull);
    });

    test('lỗi mạng khi refresh KHÔNG làm mất phiên đăng nhập', () async {
      final store = InMemoryTokenStore(_pair('old', 'r1'));
      final coordinator = TokenRefreshCoordinator(
        store: store,
        refreshCall: (_) async => throw ApiException.network(),
      );

      await expectLater(
        coordinator.refresh(staleAccessToken: 'old'),
        throwsA(isA<ApiException>()),
      );
      expect(store.current, isNotNull);
      expect(store.current!.refreshToken, equals('r1'));
    });

    test('chưa đăng nhập thì báo phiên hết hạn ngay', () async {
      final coordinator = TokenRefreshCoordinator(
        store: InMemoryTokenStore(),
        refreshCall: (_) async => _pair('a', 'b'),
      );
      await expectLater(
        coordinator.refresh(),
        throwsA(isA<SessionExpiredException>()),
      );
      expect(coordinator.refreshCalls, equals(0));
    });
  });
}
