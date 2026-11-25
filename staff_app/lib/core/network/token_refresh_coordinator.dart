import '../storage/token_store.dart';
import 'api_exception.dart';
import 'single_flight.dart';

/// Phiên đăng nhập đã chấm dứt (refresh token sai, hết hạn, bị dùng lại hoặc
/// tài khoản bị khóa): bắt buộc đăng nhập lại.
class SessionExpiredException implements Exception {
  const SessionExpiredException(this.reasonCode, [this.message]);

  final String reasonCode;
  final String? message;

  @override
  String toString() => 'SessionExpiredException($reasonCode)';
}

/// Gọi `POST /api/v1/auth/refresh` với refresh token, trả cặp token mới.
typedef RefreshCall = Future<TokenPair> Function(String refreshToken);

/// Điều phối việc làm mới token theo hàng đợi tuần tự (BE-06).
///
/// - Mọi lời gọi [refresh] đồng thời dùng chung MỘT request `/auth/refresh`.
/// - Request đến muộn mang access token cũ nhưng kho đã có token mới thì không
///   gọi refresh nữa mà dùng luôn token mới.
/// - Refresh bị từ chối bằng 401 -> xóa kho token và ném
///   [SessionExpiredException]; lỗi mạng/5xx được ném lại nguyên trạng
///   ([ApiException]) và KHÔNG làm mất phiên đăng nhập.
class TokenRefreshCoordinator {
  TokenRefreshCoordinator({
    required TokenStore store,
    required RefreshCall refreshCall,
  }) : _store = store,
       _refreshCall = refreshCall;

  final TokenStore _store;
  final RefreshCall _refreshCall;
  final SingleFlight<TokenPair> _flight = SingleFlight<TokenPair>();

  int _refreshCalls = 0;

  /// Số lần thực sự gọi `/auth/refresh` (dùng trong kiểm thử).
  int get refreshCalls => _refreshCalls;

  Future<TokenPair> refresh({String? staleAccessToken}) {
    return _flight.run(() async {
      final current = _store.current;
      if (current == null) {
        throw const SessionExpiredException(ApiErrorCodes.unauthenticated);
      }
      if (staleAccessToken != null && current.accessToken != staleAccessToken) {
        // Một request khác đã làm mới token trong lúc request này chờ.
        return current;
      }
      _refreshCalls++;
      try {
        final fresh = await _refreshCall(current.refreshToken);
        await _store.save(fresh);
        return fresh;
      } on ApiException catch (error) {
        if (error.isUnauthorized) {
          await _store.clear();
          throw SessionExpiredException(error.code, error.message);
        }
        rethrow;
      }
    });
  }
}
