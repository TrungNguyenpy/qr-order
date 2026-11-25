import 'dart:async';

import '../config/app_config.dart';
import 'realtime_message.dart';
import 'stomp_realtime_service.dart';

/// Gắn một danh sách vào STOMP theo quy ước của backend: sự kiện CHỈ là tín hiệu
/// làm mới nên mọi tin trên [topics] (gộp trong [AppConfig.realtimeRefreshDebounce])
/// và mỗi lần STOMP nối lại đều kích hoạt [onReload] (tải lại bằng REST). Khi
/// STOMP mất kết nối thì thăm dò định kỳ ([AppConfig.floorPlanFallbackPoll]).
class RealtimeReloader {
  RealtimeReloader({
    required StompRealtimeService service,
    required Set<String> topics,
    required Future<void> Function() onReload,
  }) : _service = service,
       _topics = topics,
       _onReload = onReload;

  final StompRealtimeService _service;
  final Set<String> _topics;
  final Future<void> Function() _onReload;

  Timer? _debounce;
  Timer? _poll;
  StreamSubscription<RealtimeMessage>? _messages;
  StreamSubscription<void>? _connections;
  StreamSubscription<RealtimeStatus>? _statuses;
  bool _disposed = false;

  void start() {
    _messages = _service.messages.listen((message) {
      if (_topics.contains(message.destination)) _schedule();
    });
    _connections = _service.connections.listen((_) => _schedule());
    _statuses = _service.statuses.listen(_syncPoll);
    _syncPoll(_service.status);
  }

  void _syncPoll(RealtimeStatus status) {
    _poll?.cancel();
    _poll = null;
    if (_disposed || status == RealtimeStatus.connected) return;
    _poll = Timer.periodic(AppConfig.floorPlanFallbackPoll, (_) => _fire());
  }

  void _schedule() {
    if (_disposed) return;
    _debounce?.cancel();
    _debounce = Timer(AppConfig.realtimeRefreshDebounce, _fire);
  }

  void _fire() {
    if (_disposed) return;
    unawaited(_onReload());
  }

  void dispose() {
    _disposed = true;
    _debounce?.cancel();
    _poll?.cancel();
    unawaited(_messages?.cancel());
    unawaited(_connections?.cancel());
    unawaited(_statuses?.cancel());
  }
}
