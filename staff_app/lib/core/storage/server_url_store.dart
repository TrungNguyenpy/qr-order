/// Kho địa chỉ máy chủ (không phải bí mật, nhưng phải nhớ giữa các lần mở app).
abstract class ServerUrlStore {
  /// Địa chỉ đã chuẩn hóa; nếu chưa từng lưu thì là giá trị mặc định.
  String get current;

  Future<void> save(String normalizedUrl);
}

/// Bản trong bộ nhớ, dùng cho kiểm thử.
class InMemoryServerUrlStore implements ServerUrlStore {
  InMemoryServerUrlStore(this._url);

  String _url;

  @override
  String get current => _url;

  @override
  Future<void> save(String normalizedUrl) async {
    _url = normalizedUrl;
  }
}
