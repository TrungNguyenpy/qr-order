import 'package:dio/dio.dart';

import '../storage/token_store.dart';
import 'api_exception.dart';
import 'token_refresh_coordinator.dart';

/// Gắn `Authorization: Bearer` cho mọi request và xử lý `401` theo hàng đợi
/// refresh tuần tự (BE-06):
///
/// 1. Gặp 401 -> xin [TokenRefreshCoordinator.refresh]; nhiều request 401 cùng
///    lúc chỉ tạo đúng MỘT request `POST /api/v1/auth/refresh`.
/// 2. Refresh xong -> gọi lại request gốc đúng 1 lần với token mới.
/// 3. Refresh bị từ chối (401) -> báo [onSessionExpired] (về màn hình đăng nhập).
/// 4. Refresh thất bại do mạng/5xx -> KHÔNG đăng xuất, trả lỗi mạng cho request.
/// 5. `ACCOUNT_DISABLED` -> đăng xuất ngay, không refresh.
class AuthInterceptor extends Interceptor {
  AuthInterceptor({
    required TokenStore tokenStore,
    required TokenRefreshCoordinator coordinator,
    required Dio retryClient,
    required void Function(String reasonCode) onSessionExpired,
  }) : _tokenStore = tokenStore,
       _coordinator = coordinator,
       _retryClient = retryClient,
       _onSessionExpired = onSessionExpired;

  static const String _retriedKey = 'authRetried';

  final TokenStore _tokenStore;
  final TokenRefreshCoordinator _coordinator;
  final Dio _retryClient;
  final void Function(String reasonCode) _onSessionExpired;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final token = _tokenStore.current?.accessToken;
    if (token != null && token.isNotEmpty) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }

  @override
  Future<void> onError(
    DioException err,
    ErrorInterceptorHandler handler,
  ) async {
    final options = err.requestOptions;
    if (err.response?.statusCode != 401 || options.extra[_retriedKey] == true) {
      handler.next(err);
      return;
    }

    final code = _errorCodeOf(err.response?.data);
    if (code == ApiErrorCodes.accountDisabled) {
      await _tokenStore.clear();
      _onSessionExpired(ApiErrorCodes.accountDisabled);
      handler.next(err);
      return;
    }

    final staleToken = _bearerOf(options.headers['Authorization']);
    try {
      final tokens = await _coordinator.refresh(staleAccessToken: staleToken);
      options.headers['Authorization'] = 'Bearer ${tokens.accessToken}';
      options.extra[_retriedKey] = true;
      final response = await _retryClient.fetch<Object?>(options);
      handler.resolve(response);
    } on SessionExpiredException catch (expired) {
      _onSessionExpired(expired.reasonCode);
      handler.next(err);
    } on ApiException catch (failure) {
      // Refresh thất bại vì mạng/5xx: giữ phiên, báo lỗi cho request gốc.
      handler.next(
        DioException(
          requestOptions: options,
          error: failure,
          type: DioExceptionType.unknown,
        ),
      );
    } on DioException catch (retryError) {
      handler.next(retryError);
    }
  }

  static String? _bearerOf(Object? header) {
    if (header is String && header.startsWith('Bearer ')) {
      return header.substring('Bearer '.length);
    }
    return null;
  }

  static String? _errorCodeOf(Object? body) {
    if (body is Map && body['error'] is Map) {
      final code = (body['error'] as Map)['code'];
      if (code is String) return code;
    }
    return null;
  }
}
