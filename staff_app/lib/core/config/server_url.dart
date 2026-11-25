/// Địa chỉ máy chủ không hợp lệ (hiển thị thông điệp cho người dùng).
class ServerUrlException implements Exception {
  const ServerUrlException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// Chuẩn hóa địa chỉ máy chủ người dùng nhập: thêm `http://` nếu thiếu scheme,
/// bỏ dấu `/` cuối, từ chối chuỗi rỗng/không có host/scheme lạ/query/fragment.
///
/// Ví dụ: `192.168.1.20:8080` -> `http://192.168.1.20:8080`.
String normalizeServerUrl(String input) {
  var text = input.trim();
  if (text.isEmpty) {
    throw const ServerUrlException('Địa chỉ máy chủ không được để trống.');
  }
  if (!text.contains('://')) text = 'http://$text';

  final Uri? uri = Uri.tryParse(text);
  if (uri == null || uri.host.isEmpty) {
    throw const ServerUrlException(
      'Địa chỉ máy chủ không hợp lệ. Ví dụ: 192.168.1.20:8080',
    );
  }
  if (uri.scheme != 'http' && uri.scheme != 'https') {
    throw const ServerUrlException('Chỉ hỗ trợ địa chỉ http:// hoặc https://');
  }
  if (uri.hasQuery || uri.hasFragment) {
    throw const ServerUrlException(
      'Địa chỉ máy chủ không được chứa tham số hay dấu #.',
    );
  }

  var path = uri.path;
  while (path.endsWith('/')) {
    path = path.substring(0, path.length - 1);
  }
  return Uri(
    scheme: uri.scheme,
    host: uri.host,
    port: uri.hasPort ? uri.port : null,
    path: path,
  ).toString();
}

/// Địa chỉ WebSocket thuần cho STOMP của Staff App (Flutter dùng
/// `/ws/websocket`, không dùng SockJS): `http` -> `ws`, `https` -> `wss`.
String webSocketUrlFor(
  String normalizedBaseUrl, {
  String path = '/ws/websocket',
}) {
  final uri = Uri.parse(normalizedBaseUrl);
  final scheme = uri.scheme == 'https' ? 'wss' : 'ws';
  var basePath = uri.path;
  while (basePath.endsWith('/')) {
    basePath = basePath.substring(0, basePath.length - 1);
  }
  return Uri(
    scheme: scheme,
    host: uri.host,
    port: uri.hasPort ? uri.port : null,
    path: '$basePath$path',
  ).toString();
}
