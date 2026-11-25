import '../../auth/domain/auth_models.dart';
import '../../orders/domain/order_models.dart';
import 'billing_models.dart';

/// Quyền nào được phép làm gì với hóa đơn không còn ACTIVE hoặc cần can thiệp.
/// Hàm thuần để test: không phụ thuộc widget.
class BillActionAccess {
  const BillActionAccess({
    required this.canVoid,
    required this.canWithdrawVoid,
    required this.canRefund,
    required this.canReopen,
    required this.canReplace,
  });

  /// Tính các thao tác khả dụng cho [bill] ở trạng thái Order [orderStatus].
  factory BillActionAccess.of({
    required StaffUser user,
    required Bill bill,
    required OrderStatus? orderStatus,
  }) {
    final active = bill.status == BillStatus.active;
    final noMoneyYet = bill.paidAmount == 0 && bill.pendingAmount == 0;
    return BillActionAccess(
      // Void khi hóa đơn còn hiệu lực (có thể đã thu tiền hoặc chưa).
      canVoid: active && user.has(Permission.billVoid),
      canWithdrawVoid: bill.isVoidPending && user.has(Permission.billVoid),
      canRefund: bill.needsRefund && user.has(Permission.refundRecord),
      // Mở lại phiên: hóa đơn ACTIVE chưa có tiền (nhân viên thu ngân hoặc Manager).
      canReopen:
          active &&
          noMoneyYet &&
          (user.has(Permission.paymentProcess) ||
              user.has(Permission.sessionReopen)),
      // Hóa đơn thay thế: hóa đơn đã VOIDED và order vẫn LOCKED chờ thay thế.
      canReplace:
          bill.status == BillStatus.voided &&
          orderStatus == OrderStatus.locked &&
          user.has(Permission.billVoid),
    );
  }

  final bool canVoid;
  final bool canWithdrawVoid;
  final bool canRefund;
  final bool canReopen;
  final bool canReplace;

  bool get any =>
      canVoid || canWithdrawVoid || canRefund || canReopen || canReplace;
}
