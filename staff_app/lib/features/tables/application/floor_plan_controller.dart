import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/app_config.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/providers/core_providers.dart';
import '../../../core/realtime/realtime_message.dart';
import '../../auth/application/session_controller.dart';
import '../domain/table_filters.dart';
import '../domain/table_models.dart';

/// Sơ đồ bàn: tải bằng REST, cập nhật tức thì từ `/topic/tables` (áp sự kiện rồi
/// tải lại để lấy dữ liệu chuẩn), tải lại khi STOMP nối lại, và thăm dò định kỳ
/// khi STOMP đang mất kết nối.
class FloorPlanController extends AsyncNotifier<List<FloorPlanTable>> {
  Timer? _debounce;
  Timer? _fallbackPoll;
  StreamSubscription<RealtimeMessage>? _messageSub;
  StreamSubscription<void>? _connectionSub;
  StreamSubscription<RealtimeStatus>? _statusSub;

  @override
  Future<List<FloorPlanTable>> build() async {
    // Đổi tài khoản/đăng xuất thì bỏ dữ liệu cũ (dựng lại khi được đọc lại).
    ref.watch(sessionControllerProvider.select((auth) => auth.user?.id));
    final service = ref.watch(realtimeServiceProvider);

    _messageSub = service.messages.listen(_onMessage);
    _connectionSub = service.connections.listen((_) => _scheduleReload());
    _statusSub = service.statuses.listen(_onStatus);
    _syncFallbackPoll(service.status);

    ref.onDispose(() {
      _debounce?.cancel();
      _fallbackPoll?.cancel();
      _messageSub?.cancel();
      _connectionSub?.cancel();
      _statusSub?.cancel();
    });

    return ref.read(tablesRepositoryProvider).floorPlan();
  }

  /// Kéo để làm mới / nút "Thử lại". Giữ dữ liệu cũ trên màn hình khi đang tải lại.
  Future<void> reload() async {
    try {
      final tables = await ref.read(tablesRepositoryProvider).floorPlan();
      state = AsyncData<List<FloorPlanTable>>(tables);
    } on ApiException catch (error, stack) {
      // Có dữ liệu cũ thì giữ nguyên (màn hình hiện banner lỗi), chưa có thì báo lỗi.
      if (!state.hasValue) {
        state = AsyncError<List<FloorPlanTable>>(error, stack);
      }
      rethrow;
    }
  }

  void _onMessage(RealtimeMessage message) {
    final event = TableChangedEvent.tryParse(message);
    if (event == null) return;
    final current = state.valueOrNull;
    if (current != null) {
      state = AsyncData<List<FloorPlanTable>>(applyTableEvent(current, event));
    }
    _scheduleReload();
  }

  void _onStatus(RealtimeStatus status) => _syncFallbackPoll(status);

  void _syncFallbackPoll(RealtimeStatus status) {
    _fallbackPoll?.cancel();
    _fallbackPoll = null;
    if (status == RealtimeStatus.connected) return;
    _fallbackPoll = Timer.periodic(AppConfig.floorPlanFallbackPoll, (_) {
      unawaited(_silentReload());
    });
  }

  void _scheduleReload() {
    _debounce?.cancel();
    _debounce = Timer(AppConfig.realtimeRefreshDebounce, () {
      unawaited(_silentReload());
    });
  }

  Future<void> _silentReload() async {
    try {
      await reload();
    } on ApiException {
      // Lần tải ngầm thất bại: giữ dữ liệu hiện có, lần sau thử lại.
    }
  }
}

final floorPlanProvider =
    AsyncNotifierProvider<FloorPlanController, List<FloorPlanTable>>(
      FloorPlanController.new,
    );

/// Bộ lọc, từ khóa tìm kiếm đang chọn trên sơ đồ bàn.
class TableFilterNotifier extends Notifier<TableFilter> {
  @override
  TableFilter build() => TableFilter.all;

  void select(TableFilter filter) => state = filter;
}

final tableFilterProvider = NotifierProvider<TableFilterNotifier, TableFilter>(
  TableFilterNotifier.new,
);

class TableSearchNotifier extends Notifier<String> {
  @override
  String build() => '';

  void set(String value) => state = value;
}

final tableSearchProvider = NotifierProvider<TableSearchNotifier, String>(
  TableSearchNotifier.new,
);

/// Chi tiết phiên (PIN bàn...) cho bàn đang xem.
final staffSessionProvider = FutureProvider.autoDispose
    .family<StaffSession, int>((ref, sessionId) {
      return ref.watch(tablesRepositoryProvider).session(sessionId);
    });
