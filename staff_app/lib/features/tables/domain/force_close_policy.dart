import '../../../core/network/api_exception.dart';
import '../../auth/domain/auth_models.dart';
import 'table_models.dart';

/// Quyền đóng cưỡng bức phiên (BR-S06): cần `SESSION_FORCE_CLOSE` (MANAGER có
/// mặc định) và phiên còn hoạt động (OPEN/BILLING). Hàm thuần để test.
bool canForceCloseSession(StaffUser? user, SessionStatus? status) {
  if (user == null || status == null) return false;
  return status.isActive && user.has(Permission.sessionForceClose);
}

/// Nhóm lỗi có thể gặp khi đóng cưỡng bức phiên, để chọn cách hướng dẫn người dùng.
enum ForceCloseFailure {
  /// Bill đang chờ hoàn tiền (VOID_PENDING).
  voidPending,

  /// Bill còn giao dịch thanh toán đang chờ xác nhận.
  pendingPayment,

  /// Phiên đã kết thúc (có người khác đóng trước) hoặc dữ liệu trên máy đã cũ.
  staleOrEnded,

  /// Không đủ quyền.
  forbidden,

  /// Lỗi khác (mạng, máy chủ, lý do không hợp lệ...).
  other,
}

/// Phân loại [error] của `POST /sessions/{id}/force-close`.
ForceCloseFailure classifyForceCloseError(ApiException error) {
  switch (error.code) {
    case ApiErrorCodes.billVoidPending:
      return ForceCloseFailure.voidPending;
    case ApiErrorCodes.pendingPaymentExists:
      return ForceCloseFailure.pendingPayment;
    case ApiErrorCodes.sessionFinalState:
    case ApiErrorCodes.concurrentModification:
      return ForceCloseFailure.staleOrEnded;
    case ApiErrorCodes.accessDenied:
    case ApiErrorCodes.permissionDenied:
      return ForceCloseFailure.forbidden;
  }
  if (error.statusCode == 403) return ForceCloseFailure.forbidden;
  return ForceCloseFailure.other;
}

/// Hướng dẫn xử lý bằng tiếng Việt cho từng nhóm lỗi; null = dùng thông điệp của máy chủ.
String? forceCloseGuidance(ForceCloseFailure failure) => switch (failure) {
  ForceCloseFailure.voidPending =>
    'Hóa đơn của bàn này đang chờ hoàn tiền (Void). Hãy hoàn đủ tiền cho khách '
        'hoặc rút yêu cầu Void ở màn Thu ngân, rồi đóng phiên lại.',
  ForceCloseFailure.pendingPayment =>
    'Hóa đơn còn giao dịch thanh toán đang chờ xác nhận. Hãy xác nhận '
        '"Đã nhận tiền" hoặc "Thất bại" cho giao dịch đó ở màn Thu ngân, rồi đóng phiên lại.',
  ForceCloseFailure.staleOrEnded =>
    'Phiên đã thay đổi hoặc đã kết thúc. Dữ liệu sẽ được tải lại.',
  ForceCloseFailure.forbidden =>
    'Tài khoản không có quyền đóng cưỡng bức phiên (SESSION_FORCE_CLOSE).',
  ForceCloseFailure.other => null,
};

/// Lỗi cần người dùng xử lý ở nơi khác rồi mới thử lại: giữ hộp thoại mở và giữ lý do đã nhập.
bool forceCloseNeedsOtherAction(ForceCloseFailure failure) =>
    failure == ForceCloseFailure.voidPending ||
    failure == ForceCloseFailure.pendingPayment;

/// Kiểm tra lý do bắt buộc (backend: không rỗng, tối đa 1000 ký tự).
String? validateForceCloseReason(String raw) {
  final text = raw.trim();
  if (text.isEmpty) return 'Vui lòng nhập lý do đóng cưỡng bức';
  if (text.length > 1000) return 'Lý do tối đa 1000 ký tự';
  return null;
}

/// Lý do gợi ý (bấm để điền nhanh); người dùng vẫn có thể sửa.
const List<String> forceClosePresetReasons = <String>[
  'Khách bỏ về, không thanh toán',
  'Khách rời đi, bàn bị bỏ trống',
  'Mở nhầm phiên / phiên không còn dùng',
];
