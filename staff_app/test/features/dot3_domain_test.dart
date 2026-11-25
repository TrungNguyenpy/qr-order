import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/features/auth/domain/auth_models.dart';
import 'package:staff_app/features/billing/domain/bill_access.dart';
import 'package:staff_app/features/billing/domain/billing_models.dart';
import 'package:staff_app/features/menu/domain/menu_models.dart';
import 'package:staff_app/features/orders/domain/order_models.dart';
import 'package:staff_app/features/support/domain/service_call_models.dart';

StaffUser _user(String role, [List<String> permissions = const <String>[]]) =>
    StaffUser.fromJson(<String, dynamic>{
      'id': 5,
      'username': 'u',
      'fullName': 'Người dùng',
      'role': role,
      'status': 'ACTIVE',
      'permissions': permissions,
    });

Map<String, dynamic> _billJson({
  String status = 'ACTIVE',
  int paid = 0,
  int pending = 0,
  int refundable = 0,
}) => <String, dynamic>{
  'id': 1,
  'billNumber': 'B-1',
  'orderId': 2,
  'sessionId': 3,
  'tableId': 4,
  'tableCode': 'A01',
  'status': status,
  'paymentStatus': 'UNPAID',
  'subtotalAmount': 100000,
  'discountAmount': 0,
  'netAmount': 100000,
  'serviceFeeAmount': 0,
  'totalAmount': 100000,
  'vatAmount': 0,
  'paidAmount': paid,
  'pendingAmount': pending,
  'remainingAmount': 100000 - paid,
  'lines': <Object>[],
  'payments': <Object>[],
  'version': 3,
  'remainingRefundable': refundable,
};

MenuProduct _product({int id = 1, int price = 50000}) => MenuProduct(
  id: id,
  categoryId: 1,
  name: 'Phở',
  price: price,
  available: true,
);

void main() {
  group('ServiceCall', () {
    test('đọc đủ trường', () {
      final call = ServiceCall.fromJson(<String, dynamic>{
        'id': 7,
        'sessionId': 3,
        'tableCode': 'A02',
        'type': 'REQUEST_PAYMENT',
        'status': 'OPEN',
        'note': 'Xin tính tiền',
        'version': 1,
      });
      expect(call.id, equals(7));
      expect(call.type, equals(ServiceCallType.requestPayment));
      expect(call.status, equals(ServiceCallStatus.open));
      expect(call.tableCode, equals('A02'));
    });

    test('loại lạ bị từ chối', () {
      expect(
        () => ServiceCall.fromJson(<String, dynamic>{
          'id': 1,
          'type': 'XYZ',
          'status': 'OPEN',
        }),
        throwsA(isA<FormatException>()),
      );
    });

    test('MANAGER_SUPPORT chỉ Manager xử lý', () {
      final call = ServiceCall.fromJson(<String, dynamic>{
        'id': 1,
        'sessionId': 1,
        'type': 'MANAGER_SUPPORT',
        'status': 'OPEN',
        'version': 0,
      });
      expect(call.canHandle(isManager: false), isFalse);
      expect(call.canHandle(isManager: true), isTrue);
    });

    test('CALL_STAFF ai cũng xử lý được', () {
      final call = ServiceCall.fromJson(<String, dynamic>{
        'id': 1,
        'sessionId': 1,
        'type': 'CALL_STAFF',
        'status': 'OPEN',
        'version': 0,
      });
      expect(call.canHandle(isManager: false), isTrue);
    });
  });

  group('OptionGroupDetail.validateCount', () {
    OptionGroupDetail group({
      required bool required,
      required bool multiple,
      int min = 0,
      int? max,
    }) => OptionGroupDetail(
      id: 1,
      name: 'Size',
      multiple: multiple,
      required: required,
      minSelect: min,
      maxSelect: max,
      items: const <OptionChoice>[],
    );

    test('nhóm bắt buộc phải chọn ít nhất 1', () {
      final g = group(required: true, multiple: false);
      expect(g.validateCount(0), isNotNull);
      expect(g.validateCount(1), isNull);
    });

    test('nhóm chọn một không cho chọn quá 1', () {
      final g = group(required: true, multiple: false);
      expect(g.validateCount(2), isNotNull);
    });

    test('nhóm chọn nhiều theo min/max', () {
      final g = group(required: false, multiple: true, min: 1, max: 2);
      expect(g.validateCount(0), isNotNull);
      expect(g.validateCount(2), isNull);
      expect(g.validateCount(3), isNotNull);
    });

    test('nhóm tùy chọn có thể bỏ trống', () {
      final g = group(required: false, multiple: true);
      expect(g.validateCount(0), isNull);
    });
  });

  group('Giỏ gọi món hộ', () {
    const sizeL = OptionChoice(id: 10, name: 'Lớn', extraPrice: 10000);
    const sizeM = OptionChoice(id: 11, name: 'Vừa', extraPrice: 0);

    test('đơn giá gồm phụ thu option, thành tiền nhân số lượng', () {
      final line = CartLine(
        product: _product(),
        quantity: 2,
        choices: const <OptionChoice>[sizeL],
      );
      expect(line.unitPrice, equals(60000));
      expect(line.lineTotal, equals(120000));
    });

    test('dòng giống hệt được gộp số lượng', () {
      final a = CartLine(product: _product(), quantity: 1);
      final b = CartLine(product: _product(), quantity: 2);
      final cart = addToCart(<CartLine>[a], b);
      expect(cart, hasLength(1));
      expect(cart.first.quantity, equals(3));
    });

    test('khác option hoặc ghi chú thì tách dòng', () {
      final a = CartLine(
        product: _product(),
        quantity: 1,
        choices: const <OptionChoice>[sizeL],
      );
      final b = CartLine(
        product: _product(),
        quantity: 1,
        choices: const <OptionChoice>[sizeM],
      );
      final c = CartLine(product: _product(), quantity: 1, note: 'ít hành');
      expect(addToCart(addToCart(<CartLine>[a], b), c), hasLength(3));
    });

    test('tổng giỏ và JSON gửi lên máy chủ', () {
      final cart = <CartLine>[
        CartLine(product: _product(), quantity: 2, note: ' ít hành '),
        CartLine(
          product: _product(id: 2, price: 30000),
          quantity: 1,
          choices: const <OptionChoice>[sizeL],
        ),
      ];
      expect(cartTotal(cart), equals(140000));
      final json = cart.first.toJson();
      expect(json['productId'], equals(1));
      expect(json['quantity'], equals(2));
      expect(json['note'], equals('ít hành'));
      expect(cart.last.toJson()['optionItemIds'], equals(<int>[10]));
      expect(cart.last.toJson().containsKey('note'), isFalse);
    });
  });

  group('Bill: trường hoàn tiền', () {
    test('đọc trường hoàn tiền và cờ cần hoàn', () {
      final bill = Bill.fromJson(
        _billJson(status: 'VOID_PENDING', paid: 100000, refundable: 100000),
      );
      expect(bill.isVoidPending, isTrue);
      expect(bill.needsRefund, isTrue);
      expect(bill.remainingRefundable, equals(100000));
    });

    test('đã hoàn đủ thì không còn cần hoàn', () {
      final bill = Bill.fromJson(
        _billJson(status: 'VOID_PENDING', paid: 100000, refundable: 0),
      );
      expect(bill.needsRefund, isFalse);
    });
  });

  group('RefundResult / AttentionItem', () {
    test('RefundResult đọc kết quả và cờ hoàn đủ', () {
      final result = RefundResult.fromJson(<String, dynamic>{
        'refund': <String, dynamic>{
          'id': 4,
          'billId': 1,
          'method': 'CASH',
          'amount': 50000,
          'status': 'SUCCESS',
          'reason': 'Khách đổi ý',
        },
        'summary': <String, dynamic>{
          'billStatus': 'VOIDED',
          'remainingRefundable': 0,
        },
        'voidCompleted': true,
        'replayed': false,
      });
      expect(result.voidCompleted, isTrue);
      expect(result.billStatus, equals(BillStatus.voided));
      expect(result.refund.status, equals(TransactionStatus.success));
    });

    test('AttentionItem đọc loại và số tiền còn hoàn', () {
      final item = AttentionItem.fromJson(<String, dynamic>{
        'type': 'BILL_VOID_PENDING',
        'orderId': 1,
        'sessionId': 2,
        'tableCode': 'B03',
        'remainingRefundable': 80000,
      });
      expect(item.type, equals(AttentionType.billVoidPending));
      expect(item.remainingRefundable, equals(80000));
    });

    test('AttentionItem loại lạ bị từ chối', () {
      expect(
        () => AttentionItem.fromJson(<String, dynamic>{
          'type': 'KHAC',
          'orderId': 1,
          'sessionId': 2,
        }),
        throwsA(isA<FormatException>()),
      );
    });
  });

  group('BillActionAccess', () {
    final manager = _user('MANAGER');
    final cashier = _user('STAFF', <String>['PAYMENT_PROCESS']);
    final plainStaff = _user('STAFF');

    test('Manager Void được hóa đơn ACTIVE', () {
      final access = BillActionAccess.of(
        user: manager,
        bill: Bill.fromJson(_billJson()),
        orderStatus: OrderStatus.locked,
      );
      expect(access.canVoid, isTrue);
      expect(access.canWithdrawVoid, isFalse);
      expect(access.canRefund, isFalse);
    });

    test('nhân viên không có quyền thì không thấy thao tác nào', () {
      final access = BillActionAccess.of(
        user: plainStaff,
        bill: Bill.fromJson(_billJson()),
        orderStatus: OrderStatus.locked,
      );
      expect(access.any, isFalse);
    });

    test('thu ngân mở lại phiên khi chưa có tiền, không Void được', () {
      final access = BillActionAccess.of(
        user: cashier,
        bill: Bill.fromJson(_billJson()),
        orderStatus: OrderStatus.locked,
      );
      expect(access.canReopen, isTrue);
      expect(access.canVoid, isFalse);
    });

    test('đã có tiền thu hoặc chờ xác nhận thì không mở lại phiên', () {
      expect(
        BillActionAccess.of(
          user: manager,
          bill: Bill.fromJson(_billJson(paid: 50000)),
          orderStatus: OrderStatus.locked,
        ).canReopen,
        isFalse,
      );
      expect(
        BillActionAccess.of(
          user: manager,
          bill: Bill.fromJson(_billJson(pending: 50000)),
          orderStatus: OrderStatus.locked,
        ).canReopen,
        isFalse,
      );
    });

    test('VOID_PENDING còn phải hoàn: ghi nhận hoàn và rút Void', () {
      final access = BillActionAccess.of(
        user: manager,
        bill: Bill.fromJson(
          _billJson(status: 'VOID_PENDING', paid: 100000, refundable: 100000),
        ),
        orderStatus: OrderStatus.locked,
      );
      expect(access.canRefund, isTrue);
      expect(access.canWithdrawVoid, isTrue);
      expect(access.canVoid, isFalse);
    });

    test('hóa đơn thay thế chỉ khi VOIDED và order LOCKED', () {
      final voided = Bill.fromJson(_billJson(status: 'VOIDED'));
      expect(
        BillActionAccess.of(
          user: manager,
          bill: voided,
          orderStatus: OrderStatus.locked,
        ).canReplace,
        isTrue,
      );
      expect(
        BillActionAccess.of(
          user: manager,
          bill: voided,
          orderStatus: OrderStatus.open,
        ).canReplace,
        isFalse,
      );
    });
  });

  group('canPlaceOrderIn', () {
    test('chỉ khi chưa có order hoặc order đang mở', () {
      expect(canPlaceOrderIn(null), isTrue);
      expect(canPlaceOrderIn(OrderStatus.open), isTrue);
      expect(canPlaceOrderIn(OrderStatus.locked), isFalse);
      expect(canPlaceOrderIn(OrderStatus.paid), isFalse);
    });
  });

  group('Cần xử lý: hóa đơn chờ thu tiền', () {
    AttentionItem item(String type, {int? remaining}) =>
        AttentionItem.fromJson(<String, dynamic>{
          'type': type,
          'orderId': 1,
          'sessionId': 2,
          'tableCode': 'A01',
          'remainingAmount': remaining,
        });

    test('đọc loại BILL_AWAITING_PAYMENT và số còn phải thu', () {
      final parsed = item('BILL_AWAITING_PAYMENT', remaining: 76500);
      expect(parsed.type, equals(AttentionType.awaitingPayment));
      expect(parsed.remainingAmount, equals(76500));
    });

    test('gom nhóm theo loại, bỏ nhóm rỗng, giữ thứ tự cũ trước', () {
      final groups = groupAttention(<AttentionItem>[
        item('BILL_AWAITING_PAYMENT', remaining: 1),
        item('BILL_VOID_PENDING'),
        item('BILL_AWAITING_PAYMENT', remaining: 2),
      ]);
      expect(
        groups.keys.toList(),
        equals(<AttentionType>[
          AttentionType.billVoidPending,
          AttentionType.awaitingPayment,
        ]),
      );
      expect(
        groups[AttentionType.awaitingPayment]!
            .map((e) => e.remainingAmount)
            .toList(),
        equals(<int?>[1, 2]),
      );
    });
  });
}
