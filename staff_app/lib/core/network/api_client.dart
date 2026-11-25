import 'package:dio/dio.dart';

import '../config/app_config.dart';
import '../storage/token_store.dart';
import 'api_exception.dart';
import 'auth_interceptor.dart';
import 'token_refresh_coordinator.dart';

/// Gọi REST tới backend: Dio + interceptor Bearer/refresh + phân tích
/// `ApiResponse`/`ApiError`. Mọi lỗi được ném dưới dạng [ApiException].
class ApiClient {
  /// [baseUrl] đã chuẩn hóa (không có `/` cuối). [adapter] chỉ dùng trong kiểm thử.
  ApiClient({
    required String baseUrl,
    required TokenStore tokenStore,
    required void Function(String reasonCode) onSessionExpired,
    HttpClientAdapter? adapter,
  }) : _plain = _buildDio(baseUrl, adapter),
       _dio = _buildDio(baseUrl, adapter) {
    coordinator = TokenRefreshCoordinator(
      store: tokenStore,
      refreshCall: _callRefresh,
    );
    _dio.interceptors.add(
      AuthInterceptor(
        tokenStore: tokenStore,
        coordinator: coordinator,
        retryClient: _plain,
        onSessionExpired: onSessionExpired,
      ),
    );
  }

  static Dio _buildDio(String baseUrl, HttpClientAdapter? adapter) {
    final dio = Dio(
      BaseOptions(
        baseUrl: baseUrl,
        connectTimeout: AppConfig.connectTimeout,
        sendTimeout: AppConfig.sendTimeout,
        receiveTimeout: AppConfig.receiveTimeout,
        responseType: ResponseType.json,
        headers: <String, dynamic>{'Accept': 'application/json'},
      ),
    );
    if (adapter != null) dio.httpClientAdapter = adapter;
    return dio;
  }

  /// Dio không có interceptor xác thực: login, refresh, logout và gọi lại request.
  final Dio _plain;

  /// Dio có interceptor tự gắn Bearer và làm mới token khi gặp 401.
  final Dio _dio;

  late final TokenRefreshCoordinator coordinator;

  Future<TokenPair> _callRefresh(String refreshToken) async {
    final data = await request(
      'POST',
      '${AppConfig.apiPrefix}/auth/refresh',
      data: <String, dynamic>{'refreshToken': refreshToken},
      authenticated: false,
    );
    final tokens = TokenPair.fromJson(
      data is Map ? Map<String, dynamic>.from(data) : <String, dynamic>{},
    );
    if (!tokens.isUsable) throw ApiException.unexpected();
    return tokens;
  }

  Future<Object?> get(String path, {Map<String, dynamic>? query}) =>
      request('GET', path, query: query);

  Future<Object?> post(String path, {Object? data}) =>
      request('POST', path, data: data);

  Future<Object?> patch(String path, {Object? data}) =>
      request('PATCH', path, data: data);

  Future<Object?> put(String path, {Object? data}) =>
      request('PUT', path, data: data);

  /// Gửi request và trả về `data` của `ApiResponse` (có thể null).
  /// [authenticated] = false: không gắn Bearer, không tự refresh (login/refresh/logout).
  Future<Object?> request(
    String method,
    String path, {
    Map<String, dynamic>? query,
    Object? data,
    bool authenticated = true,
  }) async {
    final client = authenticated ? _dio : _plain;
    try {
      final response = await client.request<Object?>(
        path,
        data: data,
        queryParameters: query,
        options: Options(method: method),
      );
      return unwrapEnvelope(
        response.data,
        statusCode: response.statusCode,
        retryAfterSeconds: _retryAfter(response.headers),
      );
    } on DioException catch (error) {
      throw mapDioException(error);
    }
  }

  void close() {
    _dio.close(force: true);
    _plain.close(force: true);
  }
}

int? _retryAfter(Headers? headers) {
  final raw = headers?.value('retry-after');
  return raw == null ? null : int.tryParse(raw.trim());
}

/// Chuyển [DioException] thành [ApiException] chuẩn.
ApiException mapDioException(DioException error) {
  final inner = error.error;
  if (inner is ApiException) return inner;

  final response = error.response;
  if (response != null) {
    try {
      unwrapEnvelope(
        response.data,
        statusCode: response.statusCode,
        retryAfterSeconds: _retryAfter(response.headers),
      );
    } on ApiException catch (parsed) {
      return parsed;
    }
    return ApiException.unexpected(statusCode: response.statusCode);
  }

  switch (error.type) {
    case DioExceptionType.connectionTimeout:
    case DioExceptionType.sendTimeout:
    case DioExceptionType.receiveTimeout:
      return ApiException.timeout();
    case DioExceptionType.cancel:
      return const ApiException(
        code: ClientErrorCodes.unknown,
        message: 'Yêu cầu đã bị hủy.',
      );
    case DioExceptionType.badCertificate:
      return ApiException.network('Chứng chỉ máy chủ không hợp lệ.');
    default:
      return ApiException.network();
  }
}
