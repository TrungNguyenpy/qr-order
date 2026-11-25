import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/application/session_controller.dart';
import '../../features/auth/data/auth_repository.dart';
import '../../features/billing/data/billing_repository.dart';
import '../../features/menu/data/menu_repository.dart';
import '../../features/support/data/service_call_repository.dart';
import '../../features/orders/data/orders_repository.dart';
import '../../features/tables/data/tables_repository.dart';
import '../config/server_url.dart';
import '../network/api_client.dart';
import '../realtime/stomp_realtime_service.dart';
import '../storage/secure_token_store.dart';
import '../storage/server_url_store.dart';
import '../storage/token_store.dart';

/// Kho token. Được ghi đè trong `main` bằng bản đã `load()`; mặc định là kho an toàn.
final tokenStoreProvider = Provider<TokenStore>((ref) => SecureTokenStore());

/// Kho địa chỉ máy chủ. Bắt buộc ghi đè trong `main` và trong test.
final serverUrlStoreProvider = Provider<ServerUrlStore>(
  (ref) => throw UnimplementedError('serverUrlStoreProvider chưa được ghi đè'),
);

/// Địa chỉ máy chủ hiện hành (đã chuẩn hóa). Đổi địa chỉ => `apiClientProvider`
/// và STOMP được dựng lại với địa chỉ mới.
class ServerUrlNotifier extends Notifier<String> {
  @override
  String build() => ref.watch(serverUrlStoreProvider).current;

  /// Chuẩn hóa, lưu và áp dụng [input]. Ném [ServerUrlException] nếu không hợp lệ.
  Future<String> update(String input) async {
    final normalized = normalizeServerUrl(input);
    await ref.read(serverUrlStoreProvider).save(normalized);
    state = normalized;
    return normalized;
  }
}

final serverUrlProvider = NotifierProvider<ServerUrlNotifier, String>(
  ServerUrlNotifier.new,
);

final apiClientProvider = Provider<ApiClient>((ref) {
  final client = ApiClient(
    baseUrl: ref.watch(serverUrlProvider),
    tokenStore: ref.watch(tokenStoreProvider),
    onSessionExpired: (reason) {
      // Gọi muộn (khi có 401) nên không tạo vòng phụ thuộc lúc khởi tạo.
      ref.read(sessionControllerProvider.notifier).expire(reason);
    },
  );
  ref.onDispose(client.close);
  return client;
});

final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => AuthRepository(
    ref.watch(apiClientProvider),
    ref.watch(tokenStoreProvider),
  ),
);

final tablesRepositoryProvider = Provider<TablesRepository>(
  (ref) => TablesRepository(ref.watch(apiClientProvider)),
);

final ordersRepositoryProvider = Provider<OrdersRepository>(
  (ref) => OrdersRepository(ref.watch(apiClientProvider)),
);

final serviceCallRepositoryProvider = Provider<ServiceCallRepository>(
  (ref) => ServiceCallRepository(ref.watch(apiClientProvider)),
);

final menuRepositoryProvider = Provider<MenuRepository>(
  (ref) => MenuRepository(ref.watch(apiClientProvider)),
);

final billingRepositoryProvider = Provider<BillingRepository>(
  (ref) => BillingRepository(ref.watch(apiClientProvider)),
);

/// Dịch vụ STOMP dùng chung. Vòng đời (start/stop) do `SessionController` điều khiển.
final realtimeServiceProvider = Provider<StompRealtimeService>((ref) {
  final service = StompRealtimeService(
    serverUrl: ref.watch(serverUrlProvider),
    tokenStore: ref.watch(tokenStoreProvider),
    coordinator: ref.watch(apiClientProvider).coordinator,
  );
  ref.onDispose(service.dispose);
  return service;
});
