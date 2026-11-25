import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/util/password_policy.dart';

void main() {
  group('PasswordPolicy.validate', () {
    test('chấp nhận mật khẩu đủ mạnh', () {
      expect(PasswordPolicy.validate('Abcdefg1'), isNull);
    });

    test('từ chối khi thiếu từng loại ký tự hoặc quá ngắn', () {
      expect(PasswordPolicy.validate('Abc1'), isNotNull);
      expect(PasswordPolicy.validate('abcdefg1'), isNotNull);
      expect(PasswordPolicy.validate('ABCDEFG1'), isNotNull);
      expect(PasswordPolicy.validate('Abcdefgh'), isNotNull);
    });

    test('từ chối khi vượt 72 byte UTF-8', () {
      final long = 'Aa1${'ế' * 30}'; // mỗi "ế" chiếm 3 byte => 93 byte
      expect(PasswordPolicy.validate(long), isNotNull);
    });
  });
}
