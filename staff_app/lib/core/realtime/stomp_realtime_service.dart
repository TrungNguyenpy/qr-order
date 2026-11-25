import 'dart:async';
import 'dart:math' as math;

import 'package:stomp_dart_client/stomp_dart_client.dart';

import '../../features/auth/domain/auth_models.dart';
import '../config/app_config.dart';
import '../config/server_url.dart';
import '../network/api_exception.dart';
import '../network/token_refresh_coordinator.dart';
import '../storage/token_store.dart';
import 'realtime_message.dart';

/// Kết nối STOMP (WebSocket thuần, `/ws/websocket`) tới backend.
///
/// - Header `Authorization: Bearer <access>` ở khung CONNECT; token được làm mới
///   trước khi kết nối nếu sắp hết hạn (dùng chung [TokenRefreshCoordinator]
///   với REST nên không tạo thêm request refresh song song).
/// - Tự kết nối lại với backoff lũy tiến (1s, 2s, 4s ... tối đa
///   [AppConfig.stompMaxBackoff]); mỗi lần kết nối (kể cả lần đầu) phát một tín
///   hiệu trên [connections] để màn hình tải lại dữ liệu bằng REST, vì sự kiện
///   chỉ là tín hiệu làm mới và những sự kiện lỡ trong lúc mất mạng không được phát lại.
/// - Chỉ subscribe các kênh vai trò được phép ([topicsForRole]).
class StompRealtimeService {
  StompRealtimeService({
    required String serverUrl,
    required TokenStore tokenStore,
    required TokenRefreshCoordinator coordinator,
  }) : _serverUrl = serverUrl,
       _tokenStore = tokenStore,
       _coordinator = coordinator;

  final String _serverUrl;
  final TokenStore _tokenStore;
  final TokenRefreshCoordinator _coordinator;

  final StreamController<RealtimeMessage> _messages =
      StreamController<RealtimeMessage>.broadcast();
  final StreamController<RealtimeStatus> _statuses =
      StreamController<RealtimeStatus>.broadcast();
  final StreamController<void> _connections =
      StreamController<void>.broadcast();

  RealtimeStatus _status = RealtimeStatus.disconnected;
  StompClient? _client;
  Timer? _retryTimer;
  UserRole? _role;
  int _generation = 0;
  int _attempt = 0;
  bool _disposed = false;

  /// Tin nhắn từ mọi kênh đã subscribe.
  Stream<RealtimeMessage> get messages => _messages.stream;

  /// Phát mỗi khi (kết nối lại) STOMP thành công: nơi nghe nên tải lại bằng REST.
  Stream<void> get connections => _connections.stream;

  Stream<RealtimeStatus> get statuses => _statuses.stream;

  RealtimeStatus get status => _status;

  /// Bắt đầu (hoặc khởi động lại) kết nối cho [role]. Gọi nhiều lần an toàn.
  void start(UserRole role) {
    if (_disposed) return;
    if (_role == role && _status != RealtimeStatus.disconnected) return;
    _role = role;
    _attempt = 0;
    _open();
  }

  /// Ngắt kết nối và dừng thử lại (đăng xuất).
  void stop() {
    _role = null;
    _teardown();
    _setStatus(RealtimeStatus.disconnected);
  }

  Future<void> dispose() async {
    _disposed = true;
    stop();
    await _messages.close();
    await _statuses.close();
    await _connections.close();
  }

  void _teardown() {
    _generation++;
    _retryTimer?.cancel();
    _retryTimer = null;
    final client = _client;
    _client = null;
    if (client != null) {
      try {
        client.deactivate();
      } catch (_) {
        // Đã đóng: bỏ qua.
      }
    }
  }

  Future<void> _open() async {
    _teardown();
    final role = _role;
    if (role == null || _disposed) return;
    final generation = _generation;
    _setStatus(RealtimeStatus.connecting);

    final String token;
    try {
      token = await _freshAccessToken();
    } on SessionExpiredException {
      // Lớp REST sẽ đưa người dùng về màn hình đăng nhập; không thử lại.
      if (generation == _generation) _setStatus(RealtimeStatus.disconnected);
      return;
    } on ApiException {
      _scheduleRetry(generation);
      return;
    }
    if (generation != _generation) return;

    final topics = topicsForRole(role);
    if (topics.isEmpty) {
      _setStatus(RealtimeStatus.disconnected);
      return;
    }

    late final StompClient client;
    client = StompClient(
      config: StompConfig(
        url: webSocketUrlFor(_serverUrl),
        stompConnectHeaders: <String, String>{'Authorization': 'Bearer $token'},
        webSocketConnectHeaders: <String, dynamic>{
          'Authorization': 'Bearer $token',
        },
        heartbeatIncoming: AppConfig.stompHeartbeat,
        heartbeatOutgoing: AppConfig.stompHeartbeat,
        connectionTimeout: AppConfig.stompConnectionTimeout,
        // Tự quản lý kết nối lại để luôn dùng token mới và backoff riêng.
        reconnectDelay: Duration.zero,
        onConnect: (StompFrame frame) {
          if (generation != _generation) return;
          _attempt = 0;
          for (final topic in topics) {
            client.subscribe(
              destination: topic,
              callback: (StompFrame message) {
                if (generation != _generation) return;
                final parsed = RealtimeMessage.tryParse(topic, message.body);
                if (parsed != null && !_messages.isClosed) {
                  _messages.add(parsed);
                }
              },
            );
          }
          _setStatus(RealtimeStatus.connected);
          if (!_connections.isClosed) _connections.add(null);
        },
        onStompError: (StompFrame frame) =>
            _onLost(generation, authError: true),
        onWebSocketError: (dynamic _) => _onLost(generation),
        onWebSocketDone: () => _onLost(generation),
        onDisconnect: (StompFrame frame) => _onLost(generation),
      ),
    );
    _client = client;
    client.activate();
  }

  Future<String> _freshAccessToken() async {
    final current = _tokenStore.current;
    if (current == null) {
      throw const SessionExpiredException(ApiErrorCodes.unauthenticated);
    }
    if (current.isAccessExpiringWithin(AppConfig.accessTokenSkew)) {
      final fresh = await _coordinator.refresh(
        staleAccessToken: current.accessToken,
      );
      return fresh.accessToken;
    }
    return current.accessToken;
  }

  void _onLost(int generation, {bool authError = false}) {
    if (generation != _generation || _disposed) return;
    final client = _client;
    // Lỗi socket, DONE và DISCONNECT thường đến liên tiếp: chỉ xử lý lần đầu.
    if (client == null) return;
    _client = null;
    try {
      client.deactivate();
    } catch (_) {
      // Đã đóng: bỏ qua.
    }
    _setStatus(RealtimeStatus.disconnected);
    if (_role == null) return;
    if (authError) {
      // CONNECT bị từ chối (thường do access token hết hạn): thử lại ngay với
      // token mới, REST sẽ lo việc đăng xuất nếu refresh cũng bị từ chối.
      _scheduleRetry(generation, forceRefresh: true);
    } else {
      _scheduleRetry(generation);
    }
  }

  void _scheduleRetry(int generation, {bool forceRefresh = false}) {
    if (generation != _generation || _disposed || _role == null) return;
    _retryTimer?.cancel();
    final seconds = math.min(
      AppConfig.stompMaxBackoff.inSeconds,
      1 << math.min(_attempt, 5),
    );
    _attempt++;
    _retryTimer = Timer(Duration(seconds: seconds), () async {
      if (generation != _generation || _disposed) return;
      if (forceRefresh) {
        final stale = _tokenStore.current?.accessToken;
        try {
          await _coordinator.refresh(staleAccessToken: stale);
        } on SessionExpiredException {
          return;
        } on ApiException {
          // Mạng lỗi: vòng thử lại kế tiếp xử lý.
        }
      }
      if (generation != _generation || _disposed) return;
      unawaited(_open());
    });
  }

  void _setStatus(RealtimeStatus next) {
    if (_status == next) return;
    _status = next;
    if (!_statuses.isClosed) _statuses.add(next);
  }
}
