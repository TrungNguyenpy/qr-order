import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/network/api_exception.dart';
import 'package:staff_app/core/providers/core_providers.dart';
import 'package:staff_app/features/auth/application/session_controller.dart';
import 'package:staff_app/features/auth/domain/auth_models.dart';
import 'package:staff_app/features/billing/domain/billing_models.dart';
import 'package:staff_app/features/billing/domain/write_off.dart';
import 'package:staff_app/features/billing/presentation/cashier_sheet.dart';
import 'package:staff_app/features/billing/presentation/refund_history_section.dart';
import 'package:staff_app/features/billing/presentation/refund_receipt_dialog.dart';
import 'package:staff_app/features/billing/presentation/write_off_card.dart';
import 'package:staff_app/features/orders/domain/order_models.dart';
import 'package:staff_app/features/tables/application/floor_plan_controller.dart';
import 'package:staff_app/features/tables/domain/table_models.dart';
import 'package:staff_app/features/tables/presentation/ended_session_section.dart';
import 'package:staff_app/features/tables/presentation/force_close_dialog.dart';
import 'package:staff_app/features/tables/presentation/table_detail_sheet.dart';
import 'package:staff_app/shared/widgets/state_views.dart';

import '../support/fakes.dart';

class _FakePlan extends FloorPlanController {
  _FakePlan(this._tables);

  final List<FloorPlanTable> _tables;

  @override
  Future<List<FloorPlanTable>> build() async => _tables;

  @override
  Future<void> reload() async {}
}

ApiException _apiError(String code, {int status = 409}) =>
    ApiException(code: code, message: 'Lỗi máy chủ', statusCode: status);

StaffSession _endedSession({
  SessionStatus status = SessionStatus.forceClosed,
  String? reason = 'Khách bỏ về',
}) => StaffSession(
  id: 5,
  tableId: 1,
  status: status,
  lastActivityAt: DateTime.utc(2026, 11, 25, 3),
  needsAttention: false,
  deviceCount: 1,
  version: 4,
  openedAt: DateTime.utc(2026, 11, 25, 1),
  closedAt: DateTime.utc(2026, 11, 25, 3),
  closeReason: reason,
);

SessionOrder _order(OrderStatus status, {int? shortfall, String? reason}) =>
    SessionOrder(
      orderId: 3,
      sessionId: 5,
      status: status,
      batches: const <OrderBatch>[
        OrderBatch(
          id: 1,
          seq: 1,
          status: BatchStatus.accepted,
          items: <OrderItem>[],
          version: 0,
        ),
      ],
      billableTotal: 300000,
      shortfallAmount: shortfall,
      writeoffReason: reason,
    );

Bill _writtenOffBill() => Bill.fromJson(<String, dynamic>{
  'id': 7,
  'billNumber': 'BILL-20261125-0001',
  'orderId': 3,
  'sessionId': 5,
  'tableId': 1,
  'tableCode': 'A01',
  'status': 'WRITTEN_OFF',
  'paymentStatus': 'PARTIALLY_PAID',
  'totalAmount': 300000,
  'paidAmount': 100000,
  'remainingAmount': 0,
});

RefundReceipt _receipt({int remaining = 0}) =>
    RefundReceipt.fromJson(<String, dynamic>{
      'header': <String, dynamic>{'restaurantName': 'Nhà hàng Mẫu'},
      'receiptNumber': 'RF-20261125-0001',
      'billNumber': 'BILL-20261125-0001',
      'billStatus': 'VOIDED',
      'tableCode': 'A01',
      'method': 'CASH',
      'amount': 200000,
      'reason': 'Khách đổi món',
      'billTotalAmount': 300000,
      'totalPaidSuccess': 300000,
      'refundedBefore': 100000,
      'refundedAfter': 300000 - remaining,
      'remainingToRefund': remaining,
      'footer': 'Cảm ơn quý khách',
    });

Refund _refund(int id, String status, {String? receipt}) =>
    Refund.fromJson(<String, dynamic>{
      'id': id,
      'billId': 7,
      'method': 'CASH',
      'amount': 200000,
      'status': status,
      'reason': 'Khách đổi món',
      'receiptNumber': receipt,
    });

Future<void> _pump(
  WidgetTester tester, {
  required Widget home,
  List<Override> overrides = const <Override>[],
  UserRole role = UserRole.manager,
  Set<Permission> permissions = const <Permission>{},
}) async {
  tester.view.physicalSize = const Size(1200, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final user = fakeUser(role: role, permissions: permissions);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        sessionControllerProvider.overrideWith(
          () => FixedSessionController(user),
        ),
        realtimeServiceProvider.overrideWithValue(FakeRealtimeService()),
        ...overrides,
      ],
      child: MaterialApp(home: Scaffold(body: home)),
    ),
  );
}

/// Nút "mở" gọi [open] với context của Scaffold.
Widget _opener(Future<void> Function(BuildContext context) open) => Builder(
  builder: (context) =>
      TextButton(onPressed: () => open(context), child: const Text('mở')),
);

void main() {
  group('Hộp thoại đóng cưỡng bức phiên', () {
    late FakeTablesRepository repo;
    ForceCloseResult? result;
    var finished = false;

    Future<void> openDialog(WidgetTester tester) async {
      repo = FakeTablesRepository();
      result = null;
      finished = false;
      await _pump(
        tester,
        overrides: [tablesRepositoryProvider.overrideWithValue(repo)],
        home: _opener((context) async {
          result = await showForceCloseDialog(
            context,
            sessionId: 5,
            tableCode: 'A01',
            version: 3,
          );
          finished = true;
        }),
      );
      await tester.tap(find.text('mở'));
      await tester.pumpAndSettle();
    }

    Future<void> submit(WidgetTester tester, String reason) async {
      await tester.enterText(find.byType(TextField), reason);
      await tester.tap(find.text('Đóng cưỡng bức'));
      await tester.pumpAndSettle();
    }

    testWidgets('Lý do trống: báo lỗi, không gọi API', (tester) async {
      await openDialog(tester);
      await tester.tap(find.text('Đóng cưỡng bức'));
      await tester.pump();
      expect(find.text('Vui lòng nhập lý do đóng cưỡng bức'), findsOneWidget);
      expect(repo.forceCloseCalls, isEmpty);
      expect(find.byType(ForceCloseDialog), findsOneWidget);
    });

    testWidgets('Chip lý do gợi ý điền vào ô nhập', (tester) async {
      await openDialog(tester);
      await tester.tap(find.text('Khách bỏ về, không thanh toán'));
      await tester.pump();
      expect(
        find.widgetWithText(TextField, 'Khách bỏ về, không thanh toán'),
        findsOneWidget,
      );
    });

    testWidgets('Thành công: gửi lý do đã cắt khoảng trắng + version, đóng hộp thoại', (
      tester,
    ) async {
      await openDialog(tester);
      await submit(tester, '  Khách bỏ về  ');
      expect(repo.forceCloseCalls, <String>['5:Khách bỏ về:3']);
      expect(finished, isTrue);
      expect(result?.closed?.status, SessionStatus.forceClosed);
      expect(result?.openCashier, isFalse);
      expect(find.byType(ForceCloseDialog), findsNothing);
    });

    testWidgets('BILL_VOID_PENDING: hướng dẫn hoàn tiền, giữ hộp thoại và lý do', (
      tester,
    ) async {
      await openDialog(tester);
      repo.forceCloseError = _apiError('BILL_VOID_PENDING');
      await submit(tester, 'Khách bỏ về');
      expect(find.textContaining('đang chờ hoàn tiền'), findsOneWidget);
      expect(find.byType(ForceCloseDialog), findsOneWidget);
      expect(find.widgetWithText(TextField, 'Khách bỏ về'), findsOneWidget);
      expect(find.text('Mở màn Thu ngân'), findsOneWidget);
      expect(find.text('Thử lại'), findsOneWidget);
      expect(finished, isFalse);
    });

    testWidgets('PENDING_PAYMENT_EXISTS: hướng dẫn xác nhận giao dịch chờ', (
      tester,
    ) async {
      await openDialog(tester);
      repo.forceCloseError = _apiError('PENDING_PAYMENT_EXISTS');
      await submit(tester, 'Khách bỏ về');
      expect(find.textContaining('giao dịch thanh toán đang chờ'), findsOneWidget);
      expect(find.text('Mở màn Thu ngân'), findsOneWidget);
    });

    testWidgets('Chọn Mở màn Thu ngân: trả openCashier', (tester) async {
      await openDialog(tester);
      repo.forceCloseError = _apiError('BILL_VOID_PENDING');
      await submit(tester, 'Khách bỏ về');
      await tester.tap(find.text('Mở màn Thu ngân'));
      await tester.pumpAndSettle();
      expect(result?.openCashier, isTrue);
      expect(result?.closed, isNull);
    });

    testWidgets('Sửa lỗi rồi Thử lại thì đóng được phiên', (tester) async {
      await openDialog(tester);
      repo.forceCloseError = _apiError('BILL_VOID_PENDING');
      await submit(tester, 'Khách bỏ về');
      repo.forceCloseError = null;
      await tester.tap(find.text('Thử lại'));
      await tester.pumpAndSettle();
      expect(repo.forceCloseCalls.length, 2);
      expect(result?.closed, isNotNull);
    });

    testWidgets('SESSION_FINAL_STATE: không gửi lại được, chỉ đóng hộp thoại', (
      tester,
    ) async {
      await openDialog(tester);
      repo.forceCloseError = _apiError('SESSION_FINAL_STATE');
      await submit(tester, 'Khách bỏ về');
      expect(find.textContaining('đã thay đổi hoặc đã kết thúc'), findsOneWidget);
      expect(find.text('Đóng cưỡng bức'), findsNothing);
      await tester.tap(find.text('Đóng'));
      await tester.pumpAndSettle();
      expect(finished, isTrue);
      expect(result?.closed, isNull);
    });

    testWidgets('Không đủ quyền (403): báo thiếu SESSION_FORCE_CLOSE', (
      tester,
    ) async {
      await openDialog(tester);
      repo.forceCloseError = _apiError('ACCESS_DENIED', status: 403);
      await submit(tester, 'Khách bỏ về');
      expect(find.textContaining('SESSION_FORCE_CLOSE'), findsOneWidget);
      expect(find.text('Đóng cưỡng bức'), findsNothing);
    });

    testWidgets('Hủy: không gọi API', (tester) async {
      await openDialog(tester);
      await tester.tap(find.text('Hủy'));
      await tester.pumpAndSettle();
      expect(repo.forceCloseCalls, isEmpty);
      expect(result?.closed, isNull);
      expect(finished, isTrue);
    });
  });

  group('Biên lai hoàn tiền', () {
    late FakeBillingRepository repo;

    Future<void> openReceipt(WidgetTester tester) async {
      await _pump(
        tester,
        overrides: [billingRepositoryProvider.overrideWithValue(repo)],
        home: _opener((context) => showRefundReceiptDialog(context, 9)),
      );
      await tester.tap(find.text('mở'));
      await tester.pump();
    }

    setUp(() => repo = FakeBillingRepository());

    testWidgets('Loading: hiện skeleton', (tester) async {
      repo.receiptGate = Completer<void>();
      repo.refundReceiptResult = _receipt();
      await openReceipt(tester);
      await tester.pump();
      expect(find.byType(SkeletonBox), findsWidgets);
      repo.receiptGate!.complete();
      await tester.pumpAndSettle();
    });

    testWidgets('Success: hiện số biên lai, tiền hoàn và còn phải hoàn', (
      tester,
    ) async {
      repo.refundReceiptResult = _receipt();
      await openReceipt(tester);
      await tester.pumpAndSettle();
      expect(repo.receiptCalls, <int>[9]);
      expect(find.text('Biên lai RF-20261125-0001'), findsOneWidget);
      expect(find.text('Nhà hàng Mẫu'), findsOneWidget);
      expect(find.text('Hoàn lần này (Tiền mặt)'), findsOneWidget);
      expect(find.text('200.000 đ'), findsOneWidget);
      expect(find.text('Còn phải hoàn'), findsOneWidget);
      expect(find.text('Cảm ơn quý khách'), findsOneWidget);
    });

    testWidgets('RECEIPT_NOT_AVAILABLE: báo chưa có biên lai, không có Thử lại', (
      tester,
    ) async {
      repo.receiptError = ApiException(
        code: 'RECEIPT_NOT_AVAILABLE',
        message: 'Giao dịch hoàn tiền đang PENDING, chưa có phiếu',
        statusCode: 409,
      );
      await openReceipt(tester);
      await tester.pumpAndSettle();
      expect(find.textContaining('chưa có phiếu'), findsOneWidget);
      expect(find.text('Thử lại'), findsNothing);
    });

    testWidgets('Lỗi mạng: có nút Thử lại và tải lại được', (tester) async {
      repo.receiptError = ApiException.network();
      await openReceipt(tester);
      await tester.pumpAndSettle();
      expect(find.text('Không tải được biên lai'), findsOneWidget);
      repo.receiptError = null;
      repo.refundReceiptResult = _receipt();
      await tester.tap(find.text('Thử lại'));
      await tester.pumpAndSettle();
      expect(find.text('Biên lai RF-20261125-0001'), findsOneWidget);
    });
  });

  group('Lịch sử hoàn tiền trên hóa đơn', () {
    late FakeBillingRepository repo;

    Future<void> pumpSection(WidgetTester tester) => _pump(
      tester,
      overrides: [billingRepositoryProvider.overrideWithValue(repo)],
      home: const SingleChildScrollView(child: RefundHistorySection(billId: 7)),
    );

    setUp(() => repo = FakeBillingRepository());

    testWidgets('Empty: chưa có giao dịch hoàn', (tester) async {
      await pumpSection(tester);
      await tester.pumpAndSettle();
      expect(find.text('Chưa có giao dịch hoàn tiền.'), findsOneWidget);
    });

    testWidgets('Error: có nút Thử lại', (tester) async {
      repo.refundsError = ApiException.network();
      await pumpSection(tester);
      await tester.pumpAndSettle();
      expect(find.text('Thử lại'), findsOneWidget);
    });

    testWidgets('Success: chỉ giao dịch SUCCESS có biên lai mới có nút xem', (
      tester,
    ) async {
      repo.refundList = <Refund>[
        _refund(1, 'SUCCESS', receipt: 'RF-20261125-0001'),
        _refund(2, 'PENDING'),
        _refund(3, 'FAILED'),
      ];
      repo.refundReceiptResult = _receipt();
      await pumpSection(tester);
      await tester.pumpAndSettle();
      expect(find.text('Xem biên lai'), findsOneWidget);
      expect(find.text('Biên lai RF-20261125-0001'), findsOneWidget);
      await tester.tap(find.text('Xem biên lai'));
      await tester.pumpAndSettle();
      expect(repo.receiptCalls, <int>[1]);
      expect(find.text('Biên lai hoàn tiền'), findsOneWidget);
    });
  });

  group('Thẻ thất thu', () {
    testWidgets('Hiện số tiền thất thu và các số liệu của hóa đơn', (
      tester,
    ) async {
      final info = WriteOffInfo.resolve(
        order: _order(OrderStatus.writtenOff, reason: 'Khách bỏ về'),
        bill: _writtenOffBill(),
      )!;
      await _pump(tester, home: WriteOffCard(info: info));
      expect(find.text('Đã ghi nhận thất thu'), findsOneWidget);
      expect(find.text('Số tiền thất thu'), findsOneWidget);
      expect(find.text('200.000 đ'), findsOneWidget);
      expect(find.text('300.000 đ'), findsOneWidget);
      expect(find.text('100.000 đ'), findsOneWidget);
      expect(find.text('BILL-20261125-0001'), findsOneWidget);
      expect(find.text('Khách bỏ về'), findsOneWidget);
    });

    testWidgets('Không có số liệu: báo rõ thay vì hiện 0 đ', (tester) async {
      final info = WriteOffInfo.resolve(order: _order(OrderStatus.writtenOff))!;
      await _pump(tester, home: WriteOffCard(info: info));
      expect(find.text('Chưa có số liệu'), findsOneWidget);
      expect(find.text('0 đ'), findsNothing);
    });
  });

  group('Kết quả phiên vừa kết thúc trên bàn đang dọn', () {
    late FakeTablesRepository tables;
    late FakeOrdersRepository orders;
    late FakeBillingRepository billing;

    setUp(() {
      tables = FakeTablesRepository();
      orders = FakeOrdersRepository();
      billing = FakeBillingRepository();
    });

    Future<void> pumpSection(WidgetTester tester) => _pump(
      tester,
      overrides: [
        tablesRepositoryProvider.overrideWithValue(tables),
        ordersRepositoryProvider.overrideWithValue(orders),
        billingRepositoryProvider.overrideWithValue(billing),
      ],
      home: const SingleChildScrollView(
        child: EndedSessionSection(tableId: 1, tableCode: 'A01'),
      ),
    );

    testWidgets('Phiên bị đóng cưỡng bức, Order WRITTEN_OFF chưa có Bill', (
      tester,
    ) async {
      tables.endedSession = _endedSession();
      orders.sessionOrderResult = _order(
        OrderStatus.writtenOff,
        shortfall: 120000,
        reason: 'Khách bỏ về',
      );
      await pumpSection(tester);
      await tester.pumpAndSettle();
      expect(find.text('Phiên vừa kết thúc'), findsOneWidget);
      expect(find.text('Đóng cưỡng bức'), findsOneWidget);
      expect(find.text('Đã ghi nhận thất thu'), findsOneWidget);
      expect(find.text('120.000 đ'), findsOneWidget);
      expect(find.text('Xem ở màn Thu ngân'), findsOneWidget);
    });

    testWidgets('Bill WRITTEN_OFF: suy ra thất thu từ hóa đơn', (tester) async {
      tables.endedSession = _endedSession();
      orders.sessionOrderResult = _order(OrderStatus.writtenOff);
      billing.bill = _writtenOffBill();
      await pumpSection(tester);
      await tester.pumpAndSettle();
      expect(find.text('200.000 đ'), findsOneWidget);
      expect(find.text('BILL-20261125-0001'), findsOneWidget);
    });

    testWidgets('Không có món tính tiền: Order VOIDED, không thất thu', (
      tester,
    ) async {
      tables.endedSession = _endedSession();
      orders.sessionOrderResult = _order(OrderStatus.voided);
      await pumpSection(tester);
      await tester.pumpAndSettle();
      expect(find.text('Đã ghi nhận thất thu'), findsNothing);
      expect(find.textContaining('không phát sinh thất thu'), findsOneWidget);
    });

    testWidgets('Phiên đóng bình thường: không hiện kết quả thất thu', (
      tester,
    ) async {
      tables.endedSession = _endedSession(
        status: SessionStatus.closed,
        reason: null,
      );
      await pumpSection(tester);
      await tester.pumpAndSettle();
      expect(find.text('Phiên vừa kết thúc'), findsOneWidget);
      expect(find.text('Đã đóng'), findsOneWidget);
      expect(find.text('Xem ở màn Thu ngân'), findsNothing);
      expect(find.text('Đã ghi nhận thất thu'), findsNothing);
    });

    testWidgets('Empty: bàn chưa có phiên kết thúc thì không hiện gì', (
      tester,
    ) async {
      await pumpSection(tester);
      await tester.pumpAndSettle();
      expect(find.text('Phiên vừa kết thúc'), findsNothing);
    });

    testWidgets('Error: có nút Thử lại', (tester) async {
      tables.endedSessionError = ApiException.network();
      await pumpSection(tester);
      await tester.pumpAndSettle();
      expect(find.text('Không tải được kết quả phiên vừa đóng'), findsOneWidget);
      expect(find.text('Thử lại'), findsOneWidget);
    });
  });

  group('Thu ngân với hóa đơn đã ghi nhận thất thu', () {
    late FakeOrdersRepository orders;
    late FakeBillingRepository billing;
    late FakeTablesRepository tables;

    setUp(() {
      orders = FakeOrdersRepository();
      billing = FakeBillingRepository();
      tables = FakeTablesRepository();
    });

    Future<void> openCashier(WidgetTester tester) async {
      await _pump(
        tester,
        overrides: [
          ordersRepositoryProvider.overrideWithValue(orders),
          billingRepositoryProvider.overrideWithValue(billing),
          tablesRepositoryProvider.overrideWithValue(tables),
        ],
        home: _opener(
          (context) =>
              showCashierSheet(context, sessionId: 5, tableCode: 'A01'),
        ),
      );
      await tester.tap(find.text('mở'));
      await tester.pumpAndSettle();
    }

    testWidgets('Bill WRITTEN_OFF: hiện thất thu, không có nút thu tiền', (
      tester,
    ) async {
      orders.sessionOrderResult = _order(OrderStatus.writtenOff);
      billing.bill = _writtenOffBill();
      await openCashier(tester);
      expect(find.text('Đã ghi nhận thất thu'), findsOneWidget);
      expect(find.text('200.000 đ'), findsWidgets);
      expect(find.textContaining('Thu tiền'), findsNothing);
      expect(find.text('Void hóa đơn'), findsNothing);
      expect(find.text('Tính tiền'), findsNothing);
    });

    testWidgets('Order WRITTEN_OFF chưa có Bill: hiện thất thu, không có Tính tiền', (
      tester,
    ) async {
      orders.sessionOrderResult = _order(
        OrderStatus.writtenOff,
        shortfall: 300000,
      );
      await openCashier(tester);
      expect(find.text('Đã ghi nhận thất thu'), findsOneWidget);
      expect(find.text('300.000 đ'), findsWidgets);
      expect(find.text('Tính tiền'), findsNothing);
    });

    testWidgets('Order VOIDED chưa có Bill: không cho Tính tiền', (
      tester,
    ) async {
      orders.sessionOrderResult = _order(OrderStatus.voided);
      await openCashier(tester);
      expect(find.textContaining('VOIDED'), findsOneWidget);
      expect(find.text('Tính tiền'), findsNothing);
    });
  });

  group('Chi tiết bàn: đóng cưỡng bức phiên', () {
    late FakeTablesRepository tables;
    late FakeOrdersRepository orders;
    late FakeBillingRepository billing;

    FloorPlanTable activeTable() => FloorPlanTable(
      table: fakeFloorTable(1, code: 'A01', status: TableStatus.occupied).table,
      hasActiveQr: true,
      activeSession: ActiveSessionSummary(
        id: 5,
        status: SessionStatus.open,
        lastActivityAt: DateTime.utc(2026, 11, 25),
        needsAttention: false,
        deviceCount: 1,
        version: 3,
      ),
    );

    setUp(() {
      tables = FakeTablesRepository()..sessionResult = _endedSession(
        status: SessionStatus.open,
        reason: null,
      );
      orders = FakeOrdersRepository();
      billing = FakeBillingRepository();
    });

    Future<void> openDetail(
      WidgetTester tester, {
      required FloorPlanTable table,
      UserRole role = UserRole.manager,
      Set<Permission> permissions = const <Permission>{},
    }) async {
      await _pump(
        tester,
        role: role,
        permissions: permissions,
        overrides: [
          floorPlanProvider.overrideWith(() => _FakePlan(<FloorPlanTable>[table])),
          tablesRepositoryProvider.overrideWithValue(tables),
          ordersRepositoryProvider.overrideWithValue(orders),
          billingRepositoryProvider.overrideWithValue(billing),
        ],
        home: _opener((context) => showTableDetailSheet(context, tableId: 1)),
      );
      await tester.tap(find.text('mở'));
      await tester.pumpAndSettle();
    }

    testWidgets('MANAGER thấy nút và đóng được phiên với lý do', (tester) async {
      await openDetail(tester, table: activeTable());
      final button = find.text('Đóng cưỡng bức phiên');
      await tester.ensureVisible(button);
      expect(button, findsOneWidget);
      await tester.tap(button);
      await tester.pumpAndSettle();
      expect(find.byType(ForceCloseDialog), findsOneWidget);
      await tester.enterText(find.byType(TextField), 'Khách bỏ về');
      await tester.tap(find.text('Đóng cưỡng bức'));
      await tester.pumpAndSettle();
      expect(tables.forceCloseCalls, <String>['5:Khách bỏ về:3']);
      expect(find.byType(ForceCloseDialog), findsNothing);
    });

    testWidgets('STAFF không có quyền: không thấy nút', (tester) async {
      await openDetail(tester, table: activeTable(), role: UserRole.staff);
      expect(find.text('Đóng cưỡng bức phiên'), findsNothing);
    });

    testWidgets('STAFF được cấp SESSION_FORCE_CLOSE: thấy nút', (tester) async {
      await openDetail(
        tester,
        table: activeTable(),
        role: UserRole.staff,
        permissions: <Permission>{Permission.sessionForceClose},
      );
      expect(find.text('Đóng cưỡng bức phiên'), findsOneWidget);
    });

    testWidgets('Bàn đang dọn sau đóng cưỡng bức: hiện thất thu, không có nút đóng', (
      tester,
    ) async {
      tables.endedSession = _endedSession();
      orders.sessionOrderResult = _order(
        OrderStatus.writtenOff,
        shortfall: 120000,
      );
      await openDetail(
        tester,
        table: fakeFloorTable(
          1,
          code: 'A01',
          status: TableStatus.cleaning,
        ),
      );
      expect(find.text('Phiên vừa kết thúc'), findsOneWidget);
      expect(find.text('Đã ghi nhận thất thu'), findsOneWidget);
      expect(find.text('120.000 đ'), findsOneWidget);
      expect(find.text('Đóng cưỡng bức phiên'), findsNothing);
      expect(find.text('Xác nhận dọn xong'), findsOneWidget);
    });
  });
}
