import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'token_store.dart';

/// Lưu token trong Keystore (Android) / Keychain (iOS) qua `flutter_secure_storage`.
/// Bản sao trong bộ nhớ ([current]) cập nhật TRƯỚC khi ghi đĩa để request kế
/// tiếp luôn thấy token mới nhất; lỗi ghi/đọc kho an toàn không làm hỏng phiên đang chạy.
class SecureTokenStore implements TokenStore {
  SecureTokenStore([FlutterSecureStorage? storage])
    : _storage =
          storage ??
          const FlutterSecureStorage(
            aOptions: AndroidOptions(encryptedSharedPreferences: true),
          );

  static const String _accessKey = 'auth.access_token';
  static const String _refreshKey = 'auth.refresh_token';
  static const String _accessExpiryKey = 'auth.access_expires_at';
  static const String _refreshExpiryKey = 'auth.refresh_expires_at';

  final FlutterSecureStorage _storage;
  TokenPair? _cache;

  @override
  TokenPair? get current => _cache;

  @override
  Future<void> load() async {
    try {
      final access = await _storage.read(key: _accessKey);
      final refresh = await _storage.read(key: _refreshKey);
      if (access == null || refresh == null) {
        _cache = null;
        return;
      }
      final accessExpiry = await _storage.read(key: _accessExpiryKey);
      final refreshExpiry = await _storage.read(key: _refreshExpiryKey);
      _cache = TokenPair(
        accessToken: access,
        refreshToken: refresh,
        accessTokenExpiresAt: accessExpiry == null
            ? null
            : DateTime.tryParse(accessExpiry),
        refreshTokenExpiresAt: refreshExpiry == null
            ? null
            : DateTime.tryParse(refreshExpiry),
      );
    } catch (_) {
      // Keystore hỏng hoặc dữ liệu không đọc được: coi như chưa đăng nhập.
      _cache = null;
      await _wipe();
    }
  }

  @override
  Future<void> save(TokenPair pair) async {
    _cache = pair;
    try {
      await _storage.write(key: _accessKey, value: pair.accessToken);
      await _storage.write(key: _refreshKey, value: pair.refreshToken);
      await _writeOrDelete(
        _accessExpiryKey,
        pair.accessTokenExpiresAt?.toUtc().toIso8601String(),
      );
      await _writeOrDelete(
        _refreshExpiryKey,
        pair.refreshTokenExpiresAt?.toUtc().toIso8601String(),
      );
    } catch (_) {
      // Không ghi được ra kho an toàn: phiên hiện tại vẫn dùng bản trong bộ nhớ,
      // lần mở app sau sẽ phải đăng nhập lại.
    }
  }

  @override
  Future<void> clear() async {
    _cache = null;
    await _wipe();
  }

  Future<void> _writeOrDelete(String key, String? value) async {
    if (value == null) {
      await _storage.delete(key: key);
    } else {
      await _storage.write(key: key, value: value);
    }
  }

  Future<void> _wipe() async {
    try {
      await _storage.delete(key: _accessKey);
      await _storage.delete(key: _refreshKey);
      await _storage.delete(key: _accessExpiryKey);
      await _storage.delete(key: _refreshExpiryKey);
    } catch (_) {
      // Bỏ qua: bản trong bộ nhớ đã được xóa.
    }
  }
}
