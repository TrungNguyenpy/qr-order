import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/application/session_controller.dart';
import '../network/api_exception.dart';
import '../realtime/realtime_reloader.dart';
import 'core_providers.dart';

/// Danh sách tải bằng REST và tự làm mới theo STOMP (xem [RealtimeReloader]).
/// Lớp con chỉ cần nói lấy dữ liệu thế nào và nghe kênh nào.
abstract class LiveListController<T> extends AsyncNotifier<List<T>> {
  /// Các kênh STOMP làm danh sách này cũ đi.
  Set<String> get topics;

  Future<List<T>> fetch();

  @override
  Future<List<T>> build() async {
    // Đổi tài khoản/đăng xuất: bỏ dữ liệu cũ.
    ref.watch(sessionControllerProvider.select((auth) => auth.user?.id));
    final reloader = RealtimeReloader(
      service: ref.watch(realtimeServiceProvider),
      topics: topics,
      onReload: _silentReload,
    )..start();
    ref.onDispose(reloader.dispose);
    return fetch();
  }

  /// Kéo để làm mới / sau khi thao tác. Giữ dữ liệu cũ nếu lần tải mới lỗi.
  Future<void> reload() async {
    try {
      state = AsyncData<List<T>>(await fetch());
    } on ApiException catch (error, stack) {
      if (!state.hasValue) state = AsyncError<List<T>>(error, stack);
      rethrow;
    }
  }

  Future<void> _silentReload() async {
    try {
      await reload();
    } on ApiException {
      // Giữ dữ liệu hiện có; lần sau thử lại.
    }
  }
}
