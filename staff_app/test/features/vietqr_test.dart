import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/features/billing/domain/billing_models.dart';
import 'package:staff_app/features/billing/presentation/bill_dialogs.dart';

const String _serverUrl =
    'https://img.vietqr.io/image/970436-0011001234567-compact2.png'
    '?amount=159000&addInfo=BILL-20261126-0001&accountName=NGUYEN%20VAN%20A';

Map<String, dynamic> _transferJson({String? url, String? accountNo}) =>
    <String, dynamic>{
      'bankBin': '970436',
      'accountNo': accountNo ?? '0011001234567',
      'accountName': 'NGUYEN VAN A',
      'amount': 159000,
      'transferNote': 'BILL-20261126-0001',
      'qrImageUrl': url ?? _serverUrl,
    };

Map<String, dynamic> _billJson({Map<String, dynamic>? transfer}) =>
    <String, dynamic>{
      'id': 7,
      'billNumber': 'BILL-20261126-0001',
      'orderId': 3,
      'sessionId': 5,
      'tableId': 1,
      'tableCode': 'A01',
      'status': 'ACTIVE',
      'paymentStatus': 'UNPAID',
      'totalAmount': 159000,
      'paidAmount': 0,
      'remainingAmount': 159000,
      'bankTransfer': transfer,
    };

Future<void> _openPaymentDialog(WidgetTester tester, Bill bill) async {
  tester.view.physicalSize = const Size(1200, 1800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    ProviderScope(
      child: MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () => showRecordPaymentDialog(context, bill: bill),
              child: const Text('mở'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('mở'));
  await tester.pumpAndSettle();
}

String _qrImageUrl(WidgetTester tester) {
  final image = tester.widget<Image>(find.byType(Image));
  return (image.image as NetworkImage).url;
}

void main() {
  group('BankTransferInfo', () {
    test('đọc đủ thông tin từ JSON backend', () {
      final info = BankTransferInfo.tryParse(_transferJson())!;
      expect(info.bankBin, '970436');
      expect(info.accountNo, '0011001234567');
      expect(info.accountName, 'NGUYEN VAN A');
      expect(info.amount, 159000);
      expect(info.transferNote, 'BILL-20261126-0001');
      expect(info.qrImageUrl, _serverUrl);
    });

    test('thiếu số tài khoản, URL rỗng hoặc scheme lạ: không hiển thị QR', () {
      expect(BankTransferInfo.tryParse(null), isNull);
      expect(BankTransferInfo.tryParse('x'), isNull);
      expect(BankTransferInfo.tryParse(_transferJson(accountNo: '')), isNull);
      expect(BankTransferInfo.tryParse(_transferJson(url: '')), isNull);
      expect(
        BankTransferInfo.tryParse(_transferJson(url: 'javascript:alert(1)')),
        isNull,
      );
      expect(
        BankTransferInfo.tryParse(_transferJson(url: 'file:///etc/passwd')),
        isNull,
      );
    });

    test('imageUrlFor giữ URL máy chủ khi cùng số tiền', () {
      final info = BankTransferInfo.tryParse(_transferJson())!;
      expect(info.imageUrlFor(159000), _serverUrl);
    });

    test('imageUrlFor chỉ thay amount, giữ nguyên nội dung và tên đã mã hóa', () {
      final info = BankTransferInfo.tryParse(_transferJson())!;
      expect(
        info.imageUrlFor(50000),
        'https://img.vietqr.io/image/970436-0011001234567-compact2.png'
        '?amount=50000&addInfo=BILL-20261126-0001&accountName=NGUYEN%20VAN%20A',
      );
    });

    test('imageUrlFor xử lý amount ở cuối chuỗi truy vấn hoặc chưa có', () {
      final last = BankTransferInfo.tryParse(
        _transferJson(url: 'https://qr.test/x.png?addInfo=B&amount=100'),
      )!;
      expect(
        last.imageUrlFor(25),
        'https://qr.test/x.png?addInfo=B&amount=25',
      );
      final none = BankTransferInfo.tryParse(
        _transferJson(url: 'https://qr.test/x.png?addInfo=B'),
      )!;
      expect(none.imageUrlFor(25), 'https://qr.test/x.png?addInfo=B&amount=25');
      final bare = BankTransferInfo.tryParse(
        _transferJson(url: 'https://qr.test/x.png'),
      )!;
      expect(bare.imageUrlFor(25), 'https://qr.test/x.png?amount=25');
    });

    test('Bill.fromJson: có và không có bankTransfer', () {
      expect(Bill.fromJson(_billJson()).bankTransfer, isNull);
      final bill = Bill.fromJson(_billJson(transfer: _transferJson()));
      expect(bill.bankTransfer?.amount, 159000);
    });
  });

  group('Hộp thoại Thu tiền với VietQR', () {
    Bill withBank() => Bill.fromJson(_billJson(transfer: _transferJson()));

    testWidgets('Tiền mặt (mặc định): không hiện QR', (tester) async {
      await _openPaymentDialog(tester, withBank());
      expect(find.text('Chuyển khoản VietQR'), findsNothing);
      expect(find.byType(Image), findsNothing);
    });

    testWidgets('Chọn Chuyển khoản: hiện QR theo số còn lại và thông tin tài khoản', (
      tester,
    ) async {
      await _openPaymentDialog(tester, withBank());
      await tester.tap(find.text('Chuyển khoản'));
      await tester.pump();
      expect(find.text('Chuyển khoản VietQR'), findsOneWidget);
      expect(_qrImageUrl(tester), _serverUrl);
      expect(find.text('159.000 đ'), findsWidgets);
      expect(find.text('NGUYEN VAN A'), findsOneWidget);
      expect(find.text('0011001234567'), findsOneWidget);
      expect(find.text('BILL-20261126-0001'), findsOneWidget);
    });

    testWidgets('Thu một phần: QR đổi theo số tiền đang nhập', (tester) async {
      await _openPaymentDialog(tester, withBank());
      await tester.tap(find.text('Chuyển khoản'));
      await tester.pump();
      await tester.enterText(find.byType(TextFormField).first, '50000');
      await tester.pump();
      expect(_qrImageUrl(tester), contains('amount=50000'));
      expect(find.text('50.000 đ'), findsWidgets);
    });

    testWidgets('Số tiền không hợp lệ (vượt còn lại, rỗng): QR quay về số còn lại', (
      tester,
    ) async {
      await _openPaymentDialog(tester, withBank());
      await tester.tap(find.text('Chuyển khoản'));
      await tester.pump();
      await tester.enterText(find.byType(TextFormField).first, '999999999');
      await tester.pump();
      expect(_qrImageUrl(tester), _serverUrl);
      await tester.enterText(find.byType(TextFormField).first, '');
      await tester.pump();
      expect(_qrImageUrl(tester), _serverUrl);
    });

    testWidgets('Đổi sang phương thức khác: QR biến mất', (tester) async {
      await _openPaymentDialog(tester, withBank());
      await tester.tap(find.text('Chuyển khoản'));
      await tester.pump();
      expect(find.byType(Image), findsOneWidget);
      await tester.tap(find.text('Tiền mặt'));
      await tester.pump();
      expect(find.byType(Image), findsNothing);
    });

    testWidgets('Chưa cấu hình ngân hàng: báo rõ, không có QR, vẫn ghi nhận được', (
      tester,
    ) async {
      await _openPaymentDialog(tester, Bill.fromJson(_billJson()));
      await tester.tap(find.text('Chuyển khoản'));
      await tester.pump();
      expect(find.textContaining('Chưa cấu hình tài khoản nhận tiền'), findsOneWidget);
      expect(find.byType(Image), findsNothing);
      expect(find.text('Ghi nhận'), findsOneWidget);
    });
  });
}
