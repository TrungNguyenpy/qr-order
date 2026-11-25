import 'dart:async';

/// Chạy tối đa MỘT tác vụ tại một thời điểm; mọi lời gọi đến trong lúc tác vụ
/// đang chạy dùng chung kết quả (hoặc lỗi) của tác vụ đó.
///
/// Dùng để tuần tự hóa `POST /api/v1/auth/refresh` (BE-06): hai request refresh
/// song song cùng một refresh token bị backend coi là "dùng lại" và thu hồi cả
/// phiên đăng nhập.
class SingleFlight<T> {
  Future<T>? _inFlight;

  bool get isRunning => _inFlight != null;

  Future<T> run(Future<T> Function() task) {
    final existing = _inFlight;
    if (existing != null) return existing;

    final completer = Completer<T>();
    _inFlight = completer.future;
    unawaited(() async {
      try {
        completer.complete(await task());
      } catch (error, stackTrace) {
        completer.completeError(error, stackTrace);
      } finally {
        _inFlight = null;
      }
    }());
    return completer.future;
  }
}
