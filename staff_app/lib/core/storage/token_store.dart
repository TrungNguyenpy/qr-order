import '../util/json_utils.dart';

/// Cặp token của nhân viên (đáp ứng `TokenResponse` của `/api/v1/auth/login|refresh`).
class TokenPair {
  const TokenPair({
    required this.accessToken,
    required this.refreshToken,
    this.accessTokenExpiresAt,
    this.refreshTokenExpiresAt,
  });

  factory TokenPair.fromJson(Map<String, dynamic> json) => TokenPair(
    accessToken: asString(json['accessToken']),
    refreshToken: asString(json['refreshToken']),
    accessTokenExpiresAt: asDateTime(json['accessTokenExpiresAt']),
    refreshTokenExpiresAt: asDateTime(json['refreshTokenExpiresAt']),
  );

  final String accessToken;
  final String refreshToken;
  final DateTime? accessTokenExpiresAt;
  final DateTime? refreshTokenExpiresAt;

  bool get isUsable => accessToken.isNotEmpty && refreshToken.isNotEmpty;

  /// Access token hết hạn trong vòng [skew] (hoặc đã hết hạn).
  bool isAccessExpiringWithin(Duration skew, {DateTime? now}) {
    final expiresAt = accessTokenExpiresAt;
    if (expiresAt == null) return false;
    return !expiresAt.isAfter((now ?? DateTime.now()).add(skew));
  }
}

/// Kho token. [current] đọc đồng bộ từ bộ nhớ để interceptor không phải chờ I/O
/// trên mỗi request; bản lưu bền vững nằm ở `SecureTokenStore`.
abstract class TokenStore {
  /// Nạp token đã lưu vào bộ nhớ (gọi một lần khi khởi động).
  Future<void> load();

  TokenPair? get current;

  Future<void> save(TokenPair pair);

  Future<void> clear();
}

/// Bản trong bộ nhớ, dùng cho kiểm thử.
class InMemoryTokenStore implements TokenStore {
  InMemoryTokenStore([this._pair]);

  TokenPair? _pair;

  @override
  TokenPair? get current => _pair;

  @override
  Future<void> load() async {}

  @override
  Future<void> save(TokenPair pair) async {
    _pair = pair;
  }

  @override
  Future<void> clear() async {
    _pair = null;
  }
}
