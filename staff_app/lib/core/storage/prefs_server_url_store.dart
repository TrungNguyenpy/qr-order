import 'package:shared_preferences/shared_preferences.dart';

import '../config/server_url.dart';
import 'server_url_store.dart';

/// Lưu địa chỉ máy chủ bằng `shared_preferences`.
class PrefsServerUrlStore implements ServerUrlStore {
  PrefsServerUrlStore(this._prefs, {required String defaultUrl})
    : _default = _safeNormalize(defaultUrl);

  static const String _key = 'server.base_url';

  final SharedPreferences _prefs;
  final String _default;

  static String _safeNormalize(String value) {
    try {
      return normalizeServerUrl(value);
    } on ServerUrlException {
      return 'http://10.0.2.2:8080';
    }
  }

  @override
  String get current {
    final saved = _prefs.getString(_key);
    if (saved == null) return _default;
    try {
      return normalizeServerUrl(saved);
    } on ServerUrlException {
      return _default;
    }
  }

  @override
  Future<void> save(String normalizedUrl) async {
    await _prefs.setString(_key, normalizedUrl);
  }
}
