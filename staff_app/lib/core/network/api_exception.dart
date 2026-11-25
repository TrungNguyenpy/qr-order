import '../util/json_utils.dart';

/// Mã lỗi phía client (không do backend trả).
class ClientErrorCodes {
  const ClientErrorCodes._();

  static const String network = 'NETWORK_ERROR';
  static const String timeout = 'TIMEOUT';
  static const String unexpectedResponse = 'UNEXPECTED_RESPONSE';
  static const String unknown = 'UNKNOWN_ERROR';
  static const String roleNotSupported = 'ROLE_NOT_SUPPORTED';
}

/// Mã lỗi backend (`ApiError.code`, xem `ErrorCode` của qr-order-backend) mà
/// Staff App cần phân nhánh xử lý.
class ApiErrorCodes {
  const ApiErrorCodes._();

  static const String tokenExpired = 'TOKEN_EXPIRED';
  static const String tokenInvalid = 'TOKEN_INVALID';
  static const String unauthenticated = 'UNAUTHENTICATED';
  static const String accountDisabled = 'ACCOUNT_DISABLED';
  static const String invalidCredentials = 'INVALID_CREDENTIALS';
  static const String refreshTokenInvalid = 'REFRESH_TOKEN_INVALID';
  static const String refreshTokenExpired = 'REFRESH_TOKEN_EXPIRED';
  static const String refreshTokenReused = 'REFRESH_TOKEN_REUSED';
  static const String reauthenticationFailed = 'REAUTHENTICATION_FAILED';
  static const String concurrentModification = 'CONCURRENT_MODIFICATION';
  static const String invalidTableTransition = 'INVALID_TABLE_TRANSITION';
  static const String rateLimitExceeded = 'RATE_LIMIT_EXCEEDED';
  static const String accessDenied = 'ACCESS_DENIED';
  static const String permissionDenied = 'PERMISSION_DENIED';
  static const String resourceNotFound = 'RESOURCE_NOT_FOUND';
  static const String validationFailed = 'VALIDATION_FAILED';
}

/// Chi tiết lỗi theo từng trường (validation) của `ApiError.details`.
class ApiFieldError {
  const ApiFieldError({this.field, required this.message});

  final String? field;
  final String message;
}

/// Lỗi chuẩn của mọi lời gọi REST: phản ánh `ApiResponse { success: false,
/// error { code, message, details } }` của backend hoặc lỗi mạng phía client.
class ApiException implements Exception {
  const ApiException({
    required this.code,
    required this.message,
    this.statusCode,
    this.details = const <ApiFieldError>[],
    this.retryAfterSeconds,
    this.correlationId,
  });

  factory ApiException.network([String? message]) => ApiException(
    code: ClientErrorCodes.network,
    message:
        message ??
        'Không kết nối được máy chủ. Kiểm tra mạng và địa chỉ máy chủ.',
  );

  factory ApiException.timeout() => const ApiException(
    code: ClientErrorCodes.timeout,
    message: 'Máy chủ phản hồi quá chậm. Vui lòng thử lại.',
  );

  factory ApiException.unexpected({int? statusCode, String? detail}) =>
      ApiException(
        code: ClientErrorCodes.unexpectedResponse,
        message: detail ?? 'Phản hồi từ máy chủ không đúng định dạng.',
        statusCode: statusCode,
      );

  /// Dựng từ phần thân JSON `{ success:false, error:{...}, correlationId }`.
  factory ApiException.fromEnvelope(
    Map<String, dynamic> body, {
    int? statusCode,
    int? retryAfterSeconds,
  }) {
    final error = asMap(body['error']);
    final details = asMapList(error['details'])
        .map(
          (item) => ApiFieldError(
            field: asStringOrNull(item['field']),
            message: asString(item['message']),
          ),
        )
        .toList();
    return ApiException(
      code: asString(error['code'], fallback: ClientErrorCodes.unknown),
      message: asString(error['message'], fallback: 'Đã có lỗi xảy ra.'),
      statusCode: statusCode,
      details: details,
      retryAfterSeconds: retryAfterSeconds,
      correlationId: asStringOrNull(body['correlationId']),
    );
  }

  final String code;
  final String message;
  final int? statusCode;
  final List<ApiFieldError> details;
  final int? retryAfterSeconds;
  final String? correlationId;

  /// Lỗi do không tới được máy chủ (mất mạng, sai IP, timeout).
  bool get isConnectivity =>
      code == ClientErrorCodes.network || code == ClientErrorCodes.timeout;

  bool get isServerError => (statusCode ?? 0) >= 500;

  bool get isUnauthorized => statusCode == 401;

  bool get isConflict => statusCode == 409;

  bool get isConcurrentModification =>
      code == ApiErrorCodes.concurrentModification;

  /// Thông điệp hiển thị cho nhân viên (backend đã trả tiếng Việt).
  String get userMessage {
    if (code == ApiErrorCodes.rateLimitExceeded && retryAfterSeconds != null) {
      return '$message (thử lại sau $retryAfterSeconds giây)';
    }
    return message;
  }

  /// Thông điệp lỗi của một trường (validation), nếu có.
  String? fieldMessage(String field) {
    for (final detail in details) {
      if (detail.field == field) return detail.message;
    }
    return null;
  }

  @override
  String toString() => 'ApiException($code, status=$statusCode): $message';
}

/// Bóc `data` từ thân phản hồi chuẩn `ApiResponse`; ném [ApiException] khi lỗi.
Object? unwrapEnvelope(
  Object? body, {
  int? statusCode,
  int? retryAfterSeconds,
}) {
  if (body is Map) {
    final map = Map<String, dynamic>.from(body);
    if (map['success'] == true) return map['data'];
    if (map['error'] is Map) {
      throw ApiException.fromEnvelope(
        map,
        statusCode: statusCode,
        retryAfterSeconds: retryAfterSeconds,
      );
    }
  }
  if (statusCode != null && statusCode >= 400) {
    throw ApiException(
      code: 'HTTP_$statusCode',
      message: 'Máy chủ trả lỗi $statusCode.',
      statusCode: statusCode,
      retryAfterSeconds: retryAfterSeconds,
    );
  }
  throw ApiException.unexpected(statusCode: statusCode);
}
