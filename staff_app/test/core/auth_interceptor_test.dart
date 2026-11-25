import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/network/api_client.dart';
import 'package:staff_app/core/network/api_exception.dart';
import 'package:staff_app/core/storage/token_store.dart';

/// Máy chủ giả: đường dẫn `/api/v1/ping` yêu cầu `Bearer new`.
class _FakeServer implements HttpClientAdapter {
  int refreshCalls = 0;
  int pingCalls = 0;
  bool refreshRejected = false;
  bool refreshUnreachable = false;
  bool accountDisabled = false;
  final List<String> refreshTokensSeen = <String>[];

  ResponseBody _json(int status, Map<String, dynamic> body) =>
      ResponseBody.fromString(
        jsonEncode(body),
        status,
        headers: <String, List<String>>{
          Headers.contentTypeHeader: <String>[Headers.jsonContentType],
        },
      );

  Map<String, dynamic> _error(String code, String message) => <String, dynamic>{
    'success': false,
    'error': <String, dynamic>{'code': code, 'message': message},
  };

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.path == '/api/v1/auth/refresh') {
      refreshCalls++;
      final body = options.data as Map<String, dynamic>;
      refreshTokensSeen.add(body['refreshToken'] as String);
      await Future<void>.delayed(const Duration(milliseconds: 40));
      if (refreshUnreachable) {
        throw DioException.connectionError(
          requestOptions: options,
          reason: 'offline',
        );
      }
      if (refreshRejected) {
        return _json(401, _error('REFRESH_TOKEN_REUSED', 'Phiên bị thu hồi'));
      }
      return _json(200, <String, dynamic>{
        'success': true,
        'data': <String, dynamic>{'accessToken': 'new', 'refreshToken': 'r2'},
      });
    }

    pingCalls++;
    if (accountDisabled) {
      return _json(401, _error('ACCOUNT_DISABLED', 'Tài khoản bị khóa'));
    }
    if (options.headers['Authorization'] == 'Bearer new') {
      return _json(200, <String, dynamic>{'success': true, 'data': 'pong'});
    }
    return _json(401, _error('TOKEN_EXPIRED', 'Token hết hạn'));
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  late _FakeServer server;
  late InMemoryTokenStore store;
  late List<String> expired;
  late ApiClient client;

  setUp(() {
    server = _FakeServer();
    store = InMemoryTokenStore(
      const TokenPair(accessToken: 'old', refreshToken: 'r1'),
    );
    expired = <String>[];
    client = ApiClient(
      baseUrl: 'http://test',
      tokenStore: store,
      onSessionExpired: expired.add,
      adapter: server,
    );
  });

  test('10 request cùng gặp 401 -> đúng 1 lần refresh, tất cả được thử lại thành công', () async {
    final results = await Future.wait(
      List<Future<Object?>>.generate(10, (_) => client.get('/api/v1/ping')),
    );

    expect(results.every((value) => value == 'pong'), isTrue);
    expect(server.refreshCalls, equals(1));
    expect(server.refreshTokensSeen, equals(<String>['r1']));
    expect(store.current!.accessToken, equals('new'));
    expect(store.current!.refreshToken, equals('r2'));
    expect(expired, isEmpty);
  });

  test(
    'refresh bị từ chối -> xóa token, báo hết phiên, request nhận 401',
    () async {
      server.refreshRejected = true;

      await expectLater(
        client.get('/api/v1/ping'),
        throwsA(isA<ApiException>()),
      );
      expect(store.current, isNull);
      expect(expired, equals(<String>['REFRESH_TOKEN_REUSED']));
    },
  );

  test('refresh gặp lỗi mạng -> giữ phiên, không báo hết phiên', () async {
    server.refreshUnreachable = true;

    try {
      await client.get('/api/v1/ping');
      fail('phải ném ApiException');
    } on ApiException catch (error) {
      expect(error.isConnectivity, isTrue);
    }
    expect(store.current, isNotNull);
    expect(store.current!.refreshToken, equals('r1'));
    expect(expired, isEmpty);
  });

  test('ACCOUNT_DISABLED -> đăng xuất ngay, không gọi refresh', () async {
    server.accountDisabled = true;

    await expectLater(client.get('/api/v1/ping'), throwsA(isA<ApiException>()));
    expect(server.refreshCalls, equals(0));
    expect(store.current, isNull);
    expect(expired, equals(<String>['ACCOUNT_DISABLED']));
  });
}
