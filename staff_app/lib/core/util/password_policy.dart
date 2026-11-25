import 'dart:convert';

/// Chính sách mật khẩu của backend (PasswordPolicy, GĐ1): tối thiểu 8 ký tự, có
/// chữ hoa, chữ thường, chữ số; tối đa 72 byte UTF-8 (giới hạn BCrypt).
/// Kiểm tra phía client chỉ để báo lỗi sớm; backend vẫn là nơi quyết định.
class PasswordPolicy {
  const PasswordPolicy._();

  static const int minLength = 8;
  static const int maxBytes = 72;

  /// Trả về thông điệp lỗi, hoặc null nếu mật khẩu hợp lệ.
  static String? validate(String password) {
    if (password.length < minLength) {
      return 'Mật khẩu tối thiểu $minLength ký tự.';
    }
    if (utf8.encode(password).length > maxBytes) {
      return 'Mật khẩu quá dài (tối đa $maxBytes byte).';
    }
    if (!RegExp(r'[A-Z]').hasMatch(password)) {
      return 'Mật khẩu cần ít nhất 1 chữ hoa.';
    }
    if (!RegExp(r'[a-z]').hasMatch(password)) {
      return 'Mật khẩu cần ít nhất 1 chữ thường.';
    }
    if (!RegExp(r'[0-9]').hasMatch(password)) {
      return 'Mật khẩu cần ít nhất 1 chữ số.';
    }
    return null;
  }
}
