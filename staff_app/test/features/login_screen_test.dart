import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/network/api_exception.dart';
import 'package:staff_app/core/providers/core_providers.dart';
import 'package:staff_app/core/storage/server_url_store.dart';
import 'package:staff_app/core/storage/token_store.dart';
import 'package:staff_app/features/auth/presentation/login_screen.dart';

import '../support/fakes.dart';

void main() {
  late FakeAuthRepository repository;

  Future<void> pumpLogin(WidgetTester tester) async {
    repository = FakeAuthRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          serverUrlStoreProvider.overrideWithValue(
            InMemoryServerUrlStore('http://10.0.2.2:8080'),
          ),
          tokenStoreProvider.overrideWithValue(InMemoryTokenStore()),
          authRepositoryProvider.overrideWithValue(repository),
          realtimeServiceProvider.overrideWithValue(FakeRealtimeService()),
        ],
        child: const MaterialApp(home: LoginScreen()),
      ),
    );
  }

  testWidgets('hiển thị địa chỉ máy chủ mặc định và nút đổi máy chủ', (
    tester,
  ) async {
    await pumpLogin(tester);
    expect(find.text('http://10.0.2.2:8080'), findsOneWidget);
    expect(find.text('Đổi máy chủ'), findsOneWidget);
  });

  testWidgets('để trống thì báo lỗi từng trường và không gọi API', (
    tester,
  ) async {
    await pumpLogin(tester);

    await tester.tap(find.text('Đăng nhập').last);
    await tester.pump();

    expect(find.text('Nhập tên đăng nhập'), findsOneWidget);
    expect(find.text('Nhập mật khẩu'), findsOneWidget);
    expect(repository.loginCalls, isEmpty);
  });

  testWidgets('sai thông tin -> hiện thông báo lỗi từ máy chủ', (tester) async {
    await pumpLogin(tester);
    repository.loginError = const ApiException(
      code: ApiErrorCodes.invalidCredentials,
      message: 'Sai tên đăng nhập hoặc mật khẩu',
      statusCode: 401,
    );

    await tester.enterText(find.byType(TextFormField).first, ' staff1 ');
    await tester.enterText(find.byType(TextFormField).last, 'sai-mat-khau');
    await tester.tap(find.text('Đăng nhập').last);
    await tester.pump();
    await tester.pump();

    expect(find.text('Sai tên đăng nhập hoặc mật khẩu'), findsOneWidget);
    expect(repository.loginCalls, hasLength(1));
  });
}
