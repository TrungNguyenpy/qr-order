/// Cấu hình dùng chung. Không hardcode địa chỉ máy chủ trong mã nghiệp vụ: mặc
/// định lấy từ `--dart-define=API_BASE_URL=...` (xem README) và nhân viên có thể
/// đổi ngay trên màn hình đăng nhập (lưu trên thiết bị).
class AppConfig {
  const AppConfig._();

  /// Mặc định cho Android Emulator (máy chủ chạy trên máy tính): `10.0.2.2`.
  /// Trên thiết bị thật đổi sang IP LAN của máy chủ, ví dụ `192.168.1.20:8080`.
  static const String defaultApiBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://10.0.2.2:8080',
  );

  /// Tiền tố REST của backend.
  static const String apiPrefix = '/api/v1';

  /// Endpoint STOMP thuần WebSocket (không SockJS) của backend.
  static const String webSocketPath = '/ws/websocket';

  static const Duration connectTimeout = Duration(seconds: 10);
  static const Duration sendTimeout = Duration(seconds: 15);
  static const Duration receiveTimeout = Duration(seconds: 20);

  /// Làm mới access token trước khi hết hạn chừng này khi mở kết nối STOMP.
  static const Duration accessTokenSkew = Duration(seconds: 30);

  /// Nhịp tim STOMP hai chiều (khớp `WS_HEARTBEAT_MS` mặc định 10 giây).
  static const Duration stompHeartbeat = Duration(seconds: 10);

  /// Chờ tối đa khi mở WebSocket.
  static const Duration stompConnectionTimeout = Duration(seconds: 10);

  /// Trần thời gian chờ giữa hai lần kết nối lại STOMP.
  static const Duration stompMaxBackoff = Duration(seconds: 30);

  /// Khi STOMP mất kết nối, tải lại sơ đồ bàn theo chu kỳ này.
  static const Duration floorPlanFallbackPoll = Duration(seconds: 30);

  /// Gộp nhiều sự kiện realtime liên tiếp thành một lần tải lại.
  static const Duration realtimeRefreshDebounce = Duration(milliseconds: 500);

  static const String appVersionLabel = '0.3.0 (Đợt 3)';
}
