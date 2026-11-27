import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/network/api_exception.dart';
import 'package:staff_app/core/network/api_paths.dart';
import 'package:staff_app/features/auth/domain/auth_models.dart';
import 'package:staff_app/features/billing/domain/billing_models.dart';
import 'package:staff_app/features/billing/domain/write_off.dart';
import 'package:staff_app/features/billing/presentation/refund_history_section.dart';
import 'package:staff_app/features/orders/domain/order_models.dart';
import 'package:staff_app/features/tables/domain/force_close_policy.dart';
import 'package:staff_app/features/tables/domain/table_models.dart';

import '../support/fakes.dart';

Map<String, dynamic> _billJson({
  String status = 'ACTIVE',
  int total = 300000,
  int paid = 100000,
  Map<String, dynamic> extra = const <String, dynamic>{},
}) => <String, dynamic>{
  'id': 7,
  'billNumber': 'BILL-20261125-0001',
  'orderId': 3,
  'sessionId': 5,
  'tableId': 1,
  'tableCode': 'A01',
  'status': status,
  'paymentStatus': 'PARTIALLY_PAID',
  'totalAmount': total,
  'paidAmount': paid,
  ...extra,
};

SessionOrder _order({
  OrderStatus? status,
  int? shortfall,
  String? reason,
}) => SessionOrder(
  sessionId: 5,
  orderId: 3,
  status: status,
  batches: const <OrderBatch>[],
  billableTotal: 0,
  shortfallAmount: shortfall,
  writeoffReason: reason,
);

ApiException _error(String code, {int? status}) =>
    ApiException(code: code, message: 'msg', statusCode: status);

void main() {
  group('SessionOrder / Bill: thông tin thất thu', () {
    test('SessionOrder đọc shortfall, lý do, thời điểm khi WRITTEN_OFF', () {
      final order = SessionOrder.fromJson(<String, dynamic>{
        'orderId': 3,
        'sessionId': 5,
        'status': 'WRITTEN_OFF',
        'batches': <Object>[],
        'billableTotal': 200000,
        'shortfallAmount': 200000,
        'writeoffReason': 'Khách bỏ về',
        'writtenOffAt': '2026-11-25T10:00:00Z',
      });
      expect(order.isWrittenOff, isTrue);
      expect(order.shortfallAmount, 200000);
      expect(order.writeoffReason, 'Khách bỏ về');
      expect(order.writtenOffAt, isNotNull);
    });

    test('Máy chủ cũ không gửi shortfall: các trường là null', () {
      final order = SessionOrder.fromJson(<String, dynamic>{
        'orderId': 3,
        'sessionId': 5,
        'status': 'OPEN',
        'batches': <Object>[],
        'billableTotal': 0,
      });
      expect(order.isWrittenOff, isFalse);
      expect(order.shortfallAmount, isNull);
      expect(order.writtenOffAt, isNull);
    });

    test('Bill WRITTEN_OFF: thất thu = Tổng - tiền thu ròng', () {
      final bill = Bill.fromJson(_billJson(status: 'WRITTEN_OFF'));
      expect(bill.isWrittenOff, isTrue);
      expect(bill.shortfallAmount, 200000);
    });

    test('Bill WRITTEN_OFF chưa thu đồng nào: thất thu bằng Tổng', () {
      final bill = Bill.fromJson(_billJson(status: 'WRITTEN_OFF', paid: 0));
      expect(bill.shortfallAmount, 300000);
    });

    test('Bill không WRITTEN_OFF: không có thất thu', () {
      expect(Bill.fromJson(_billJson()).shortfallAmount, isNull);
      expect(
        Bill.fromJson(_billJson(status: 'VOID_PENDING')).shortfallAmount,
        isNull,
      );
    });

    test('Thu ròng lớn hơn tổng không cho thất thu âm', () {
      final bill = Bill.fromJson(
        _billJson(status: 'WRITTEN_OFF', total: 100000, paid: 150000),
      );
      expect(bill.shortfallAmount, 0);
    });
  });

  group('WriteOffInfo.resolve', () {
    test('Không WRITTEN_OFF: trả null', () {
      expect(WriteOffInfo.resolve(), isNull);
      expect(
        WriteOffInfo.resolve(
          order: _order(status: OrderStatus.paid),
          bill: Bill.fromJson(_billJson()),
        ),
        isNull,
      );
    });

    test('Order WRITTEN_OFF chưa có Bill: dùng shortfall của Order', () {
      final info = WriteOffInfo.resolve(
        order: _order(
          status: OrderStatus.writtenOff,
          shortfall: 100000,
          reason: 'Khách bỏ về',
        ),
      )!;
      expect(info.shortfallAmount, 100000);
      expect(info.hasBill, isFalse);
      expect(info.totalAmount, isNull);
      expect(info.reason, 'Khách bỏ về');
    });

    test('Bill WRITTEN_OFF, Order thiếu shortfall: suy ra từ Bill', () {
      final info = WriteOffInfo.resolve(
        order: _order(status: OrderStatus.writtenOff),
        bill: Bill.fromJson(_billJson(status: 'WRITTEN_OFF')),
      )!;
      expect(info.shortfallAmount, 200000);
      expect(info.totalAmount, 300000);
      expect(info.collectedAmount, 100000);
      expect(info.billNumber, 'BILL-20261125-0001');
      expect(info.hasBill, isTrue);
    });

    test('Shortfall do máy chủ lưu ở Order được ưu tiên hơn số suy ra', () {
      final info = WriteOffInfo.resolve(
        order: _order(status: OrderStatus.writtenOff, shortfall: 210000),
        bill: Bill.fromJson(_billJson(status: 'WRITTEN_OFF')),
      )!;
      expect(info.shortfallAmount, 210000);
    });

    test('Order WRITTEN_OFF, không có Bill, máy chủ cũ: số tiền chưa có', () {
      final info = WriteOffInfo.resolve(
        order: _order(status: OrderStatus.writtenOff),
      )!;
      expect(info.shortfallAmount, isNull);
    });

    test('Bill không WRITTEN_OFF của Order WRITTEN_OFF không bị lẫn vào', () {
      final info = WriteOffInfo.resolve(
        order: _order(status: OrderStatus.writtenOff, shortfall: 5000),
        bill: Bill.fromJson(_billJson(status: 'VOIDED')),
      )!;
      expect(info.billNumber, isNull);
      expect(info.shortfallAmount, 5000);
    });
  });

  group('Quyền và lỗi đóng cưỡng bức phiên', () {
    test('MANAGER đóng được phiên OPEN/BILLING', () {
      final manager = fakeUser(role: UserRole.manager);
      expect(canForceCloseSession(manager, SessionStatus.open), isTrue);
      expect(canForceCloseSession(manager, SessionStatus.billing), isTrue);
    });

    test('STAFF cần được cấp SESSION_FORCE_CLOSE', () {
      expect(
        canForceCloseSession(fakeUser(), SessionStatus.open),
        isFalse,
      );
      expect(
        canForceCloseSession(
          fakeUser(permissions: <Permission>{Permission.sessionForceClose}),
          SessionStatus.open,
        ),
        isTrue,
      );
      // Quyền khác không thay thế được.
      expect(
        canForceCloseSession(
          fakeUser(permissions: <Permission>{Permission.sessionReopen}),
          SessionStatus.open,
        ),
        isFalse,
      );
    });

    test('Phiên đã kết thúc hoặc chưa đăng nhập: không đóng được', () {
      final manager = fakeUser(role: UserRole.manager);
      expect(canForceCloseSession(manager, SessionStatus.closed), isFalse);
      expect(canForceCloseSession(manager, SessionStatus.forceClosed), isFalse);
      expect(canForceCloseSession(manager, SessionStatus.expired), isFalse);
      expect(canForceCloseSession(manager, null), isFalse);
      expect(canForceCloseSession(null, SessionStatus.open), isFalse);
    });

    test('Phân loại lỗi theo mã backend', () {
      expect(
        classifyForceCloseError(_error('BILL_VOID_PENDING', status: 409)),
        ForceCloseFailure.voidPending,
      );
      expect(
        classifyForceCloseError(_error('PENDING_PAYMENT_EXISTS', status: 409)),
        ForceCloseFailure.pendingPayment,
      );
      expect(
        classifyForceCloseError(_error('SESSION_FINAL_STATE', status: 409)),
        ForceCloseFailure.staleOrEnded,
      );
      expect(
        classifyForceCloseError(_error('CONCURRENT_MODIFICATION', status: 409)),
        ForceCloseFailure.staleOrEnded,
      );
      expect(
        classifyForceCloseError(_error('ACCESS_DENIED', status: 403)),
        ForceCloseFailure.forbidden,
      );
      expect(
        classifyForceCloseError(_error('SOMETHING_ELSE', status: 403)),
        ForceCloseFailure.forbidden,
      );
      expect(
        classifyForceCloseError(_error('NETWORK_ERROR')),
        ForceCloseFailure.other,
      );
    });

    test('Chỉ hai lỗi Bill cần xử lý ở nơi khác và có hướng dẫn tiếng Việt', () {
      for (final failure in ForceCloseFailure.values) {
        final needsOther =
            failure == ForceCloseFailure.voidPending ||
            failure == ForceCloseFailure.pendingPayment;
        expect(forceCloseNeedsOtherAction(failure), needsOther);
      }
      expect(forceCloseGuidance(ForceCloseFailure.voidPending), contains('hoàn tiền'));
      expect(
        forceCloseGuidance(ForceCloseFailure.pendingPayment),
        contains('đang chờ'),
      );
      expect(forceCloseGuidance(ForceCloseFailure.other), isNull);
    });

    test('Lý do bắt buộc, tối đa 1000 ký tự', () {
      expect(validateForceCloseReason(''), isNotNull);
      expect(validateForceCloseReason('   '), isNotNull);
      expect(validateForceCloseReason('Khách bỏ về'), isNull);
      expect(validateForceCloseReason('a' * 1000), isNull);
      expect(validateForceCloseReason('a' * 1001), isNotNull);
    });

    test('Đường dẫn force-close và danh sách phiên khớp backend', () {
      expect(
        ApiPaths.sessionForceClose(5),
        '/api/v1/sessions/5/force-close',
      );
      expect(ApiPaths.sessions, '/api/v1/sessions');
      expect(ApiPaths.refundReceipt(9), '/api/v1/refunds/9/receipt');
    });
  });

  group('Biên lai hoàn tiền', () {
    final json = <String, dynamic>{
      'header': <String, dynamic>{
        'restaurantName': 'Nhà hàng Mẫu',
        'address': '1 Đường A',
        'phone': '0900000000',
        'taxCode': '0312345678',
      },
      'receiptNumber': 'RF-20261125-0001',
      'issuedAt': '2026-11-25T10:00:00Z',
      'billNumber': 'BILL-20261125-0001',
      'billStatus': 'VOIDED',
      'tableCode': 'A01',
      'tableName': 'Bàn 1',
      'managerName': 'Quản lý',
      'method': 'CASH',
      'amount': 200000,
      'reason': 'Khách đổi món',
      'voidReason': 'Nhập sai',
      'billTotalAmount': 300000,
      'totalPaidSuccess': 300000,
      'refundedBefore': 100000,
      'refundedAfter': 300000,
      'remainingToRefund': 0,
      'footer': 'Cảm ơn quý khách',
    };

    test('Đọc đủ các trường', () {
      final receipt = RefundReceipt.fromJson(json);
      expect(receipt.receiptNumber, 'RF-20261125-0001');
      expect(receipt.restaurantName, 'Nhà hàng Mẫu');
      expect(receipt.taxCode, '0312345678');
      expect(receipt.billStatus, BillStatus.voided);
      expect(receipt.method, PaymentMethod.cash);
      expect(receipt.amount, 200000);
      expect(receipt.refundedBefore, 100000);
      expect(receipt.refundedAfter, 300000);
      expect(receipt.isFullyRefunded, isTrue);
    });

    test('Còn phải hoàn thì chưa hoàn đủ', () {
      final receipt = RefundReceipt.fromJson(<String, dynamic>{
        ...json,
        'refundedAfter': 100000,
        'remainingToRefund': 200000,
      });
      expect(receipt.isFullyRefunded, isFalse);
    });

    test('Thiếu số biên lai: FormatException', () {
      final broken = <String, dynamic>{...json}..remove('receiptNumber');
      expect(() => RefundReceipt.fromJson(broken), throwsFormatException);
    });

    test('Chỉ giao dịch hoàn SUCCESS có mã RF- mới có biên lai', () {
      Refund refund(String status, String? number) => Refund.fromJson(
        <String, dynamic>{
          'id': 1,
          'billId': 7,
          'method': 'CASH',
          'amount': 1000,
          'status': status,
          'reason': 'x',
          'receiptNumber': number,
        },
      );
      expect(refund('SUCCESS', 'RF-1').hasReceipt, isTrue);
      expect(refund('SUCCESS', null).hasReceipt, isFalse);
      expect(refund('PENDING', null).hasReceipt, isFalse);
      expect(refund('FAILED', null).hasReceipt, isFalse);
    });

    test('Hóa đơn nào có thể có giao dịch hoàn', () {
      bool may(Map<String, dynamic> j) =>
          billMayHaveRefunds(Bill.fromJson(_billJson(extra: j)));
      expect(may(<String, dynamic>{}), isFalse);
      expect(may(<String, dynamic>{'refundedAmount': 1}), isTrue);
      expect(may(<String, dynamic>{'refundPendingAmount': 1}), isTrue);
      expect(may(<String, dynamic>{'status': 'VOID_PENDING'}), isTrue);
      expect(may(<String, dynamic>{'status': 'VOIDED'}), isTrue);
      expect(may(<String, dynamic>{'status': 'WRITTEN_OFF'}), isFalse);
    });
  });
}
