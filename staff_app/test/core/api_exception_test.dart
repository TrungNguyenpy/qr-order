import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/network/api_exception.dart';

void main() {
  group('unwrapEnvelope', () {
    test('trả về data khi success=true', () {
      final data = unwrapEnvelope(<String, dynamic>{
        'success': true,
        'data': <String, dynamic>{'id': 7},
      }, statusCode: 200);
      expect(data, equals(<String, dynamic>{'id': 7}));
    });

    test('success=true không có data trả về null', () {
      expect(
        unwrapEnvelope(<String, dynamic>{'success': true}, statusCode: 200),
        isNull,
      );
    });

    test('đọc mã lỗi, thông điệp, chi tiết trường và correlationId', () {
      try {
        unwrapEnvelope(<String, dynamic>{
          'success': false,
          'error': <String, dynamic>{
            'code': 'VALIDATION_FAILED',
            'message': 'Dữ liệu không hợp lệ',
            'details': <Map<String, dynamic>>[
              <String, dynamic>{'field': 'username', 'message': 'Bắt buộc'},
            ],
          },
          'correlationId': 'abc-123',
        }, statusCode: 400);
        fail('phải ném ApiException');
      } on ApiException catch (error) {
        expect(error.code, equals('VALIDATION_FAILED'));
        expect(error.statusCode, equals(400));
        expect(error.fieldMessage('username'), equals('Bắt buộc'));
        expect(error.fieldMessage('password'), isNull);
        expect(error.correlationId, equals('abc-123'));
      }
    });

    test('thân không phải phong bì nhưng HTTP >= 400 vẫn ra ApiException', () {
      try {
        unwrapEnvelope('<html>Bad gateway</html>', statusCode: 502);
        fail('phải ném ApiException');
      } on ApiException catch (error) {
        expect(error.statusCode, equals(502));
        expect(error.isServerError, isTrue);
      }
    });

    test('thân lạ với HTTP 200 là UNEXPECTED_RESPONSE', () {
      try {
        unwrapEnvelope(<String, dynamic>{'status': 'UP'}, statusCode: 200);
        fail('phải ném ApiException');
      } on ApiException catch (error) {
        expect(error.code, equals(ClientErrorCodes.unexpectedResponse));
      }
    });
  });

  group('ApiException', () {
    test('phân loại 401 / 409 / mạng', () {
      const unauthorized = ApiException(
        code: ApiErrorCodes.tokenExpired,
        message: 'x',
        statusCode: 401,
      );
      const conflict = ApiException(
        code: ApiErrorCodes.concurrentModification,
        message: 'x',
        statusCode: 409,
      );
      expect(unauthorized.isUnauthorized, isTrue);
      expect(conflict.isConflict, isTrue);
      expect(conflict.isConcurrentModification, isTrue);
      expect(ApiException.network().isConnectivity, isTrue);
      expect(ApiException.timeout().isConnectivity, isTrue);
    });

    test('userMessage kèm thời gian chờ khi bị giới hạn tần suất', () {
      const limited = ApiException(
        code: ApiErrorCodes.rateLimitExceeded,
        message: 'Quá nhiều lần thử',
        statusCode: 429,
        retryAfterSeconds: 30,
      );
      expect(limited.userMessage, contains('30'));
    });
  });
}
