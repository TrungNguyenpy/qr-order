import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/util/uuid.dart';
import 'package:staff_app/features/billing/domain/billing_models.dart';
import 'package:staff_app/features/orders/domain/order_action_models.dart';

void main() {
  group('validateDiscountValue', () {
    test('phần trăm hợp lệ và vượt 100', () {
      expect(validateDiscountValue(DiscountKind.percent, '10'), isNull);
      expect(validateDiscountValue(DiscountKind.percent, '100'), isNull);
      expect(validateDiscountValue(DiscountKind.percent, '101'), isNotNull);
    });

    test('rỗng, chữ, bằng 0 đều bị từ chối', () {
      expect(validateDiscountValue(DiscountKind.percent, ''), isNotNull);
      expect(validateDiscountValue(DiscountKind.percent, 'abc'), isNotNull);
      expect(validateDiscountValue(DiscountKind.fixedAmount, '0'), isNotNull);
    });

    test('số tiền cố định không vượt tạm tính', () {
      expect(
        validateDiscountValue(
          DiscountKind.fixedAmount,
          '50000',
          subtotal: 100000,
        ),
        isNull,
      );
      expect(
        validateDiscountValue(
          DiscountKind.fixedAmount,
          '150000',
          subtotal: 100000,
        ),
        isNotNull,
      );
    });
  });

  group('generateUuidV4', () {
    final pattern = RegExp(
      r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
    );

    test('đúng định dạng v4', () {
      expect(pattern.hasMatch(generateUuidV4()), isTrue);
    });

    test('hai lần sinh khác nhau', () {
      expect(generateUuidV4() == generateUuidV4(), isFalse);
    });
  });

  group('KitchenItem.fromJson', () {
    test('đọc đủ trường và tóm tắt tùy chọn', () {
      final item = KitchenItem.fromJson(<String, dynamic>{
        'itemId': 5,
        'orderId': 2,
        'batchId': 3,
        'sessionId': 4,
        'tableCode': 'A01',
        'productName': 'Phở bò',
        'quantity': 2,
        'note': 'ít hành',
        'options': <Map<String, dynamic>>[
          <String, dynamic>{
            'groupName': 'Thịt',
            'optionName': 'Tái',
            'extraPrice': 0,
          },
          <String, dynamic>{
            'groupName': 'Size',
            'optionName': 'Lớn',
            'extraPrice': 10000,
          },
        ],
        'status': 'PREPARING',
        'waitingSeconds': 125,
        'version': 7,
      });
      expect(item.itemId, equals(5));
      expect(item.tableCode, equals('A01'));
      expect(item.quantity, equals(2));
      expect(item.waitingSeconds, equals(125));
      expect(item.optionSummary, equals('Tái, Lớn'));
    });
  });

  group('PaymentResult.fromJson', () {
    test('đọc kết quả thu tiền và cờ đóng phiên', () {
      final result = PaymentResult.fromJson(<String, dynamic>{
        'payment': <String, dynamic>{
          'id': 9,
          'method': 'CASH',
          'amount': 100000,
          'status': 'SUCCESS',
        },
        'bill': <String, dynamic>{
          'id': 1,
          'orderId': 2,
          'status': 'PAID',
          'totalAmount': 100000,
          'paidAmount': 100000,
          'remainingAmount': 0,
          'paymentStatus': 'PAID',
        },
        'sessionClosed': true,
        'replayed': false,
      });
      expect(result.sessionClosed, isTrue);
      expect(result.replayed, isFalse);
      expect(result.remainingAmount, equals(0));
    });
  });

  group('ReasonPreset', () {
    test('mã hợp lệ', () {
      for (final preset in ReasonPreset.rejection) {
        expect(ReasonPreset.isValidCode(preset.code), isTrue);
      }
      expect(ReasonPreset.isValidCode('OUT_OF_STOCK'), isTrue);
      expect(ReasonPreset.isValidCode('bad code'), isFalse);
      expect(ReasonPreset.isValidCode(''), isFalse);
    });
  });
}
