import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/network/api_exception.dart';
import 'package:staff_app/core/providers/core_providers.dart';
import 'package:staff_app/features/auth/application/session_controller.dart';
import 'package:staff_app/features/auth/domain/auth_models.dart';
import 'package:staff_app/features/billing/presentation/cashier_screen.dart';
import 'package:staff_app/features/kitchen/application/kitchen_controller.dart';
import 'package:staff_app/features/kitchen/presentation/kitchen_screen.dart';
import 'package:staff_app/features/menu/domain/menu_models.dart';
import 'package:staff_app/features/orders/application/order_lists.dart';
import 'package:staff_app/features/orders/domain/order_action_models.dart';
import 'package:staff_app/features/orders/domain/order_models.dart';
import 'package:staff_app/features/orders/presentation/item_edit_menu.dart';
import 'package:staff_app/features/orders/presentation/orders_screen.dart';
import 'package:staff_app/features/orders/presentation/place_order_sheet.dart';
import 'package:staff_app/features/support/application/service_call_providers.dart';
import 'package:staff_app/features/support/domain/service_call_models.dart';
import 'package:staff_app/features/support/presentation/service_calls_tab.dart';
import 'package:staff_app/features/tables/domain/table_models.dart';
import 'package:staff_app/shared/widgets/state_views.dart';

import '../support/fakes.dart';

class _PendingFake extends PendingBatchesController {
  _PendingFake(this._loader);

  final Future<List<OrderBatch>> Function() _loader;

  @override
  Future<List<OrderBatch>> build() => _loader();

  @override
  Future<void> reload() async {}
}

class _ReadyFake extends ReadyItemsController {
  @override
  Future<List<KitchenItem>> build() async => const <KitchenItem>[];
}

class _CancelFake extends CancelRequestsController {
  @override
  Future<List<CancelRequest>> build() async => const <CancelRequest>[];
}

class _CallsFake extends ServiceCallsController {
  _CallsFake(this._loader);

  final Future<List<ServiceCall>> Function() _loader;

  @override
  Future<List<ServiceCall>> build() => _loader();

  @override
  Future<void> reload() async {}
}

class _KitchenFake extends KitchenController {
  _KitchenFake(this._loader);

  final Future<List<KitchenItem>> Function() _loader;

  @override
  Future<List<KitchenItem>> build() => _loader();

  @override
  Future<void> reload() async {}
}

KitchenItem _kitchenItem(int id, OrderItemStatus status) => KitchenItem(
  itemId: id,
  orderId: 1,
  batchId: 1,
  sessionId: 1,
  tableCode: 'A0$id',
  productName: 'Phở bò $id',
  quantity: 2,
  status: status,
  options: const <OrderOption>[],
  waitingSeconds: 60,
  version: 4,
);

OrderBatch _batch(int id, {String table = 'A01'}) => OrderBatch(
  id: id,
  seq: 1,
  status: BatchStatus.pending,
  items: const <OrderItem>[],
  version: 0,
  tableCode: table,
  sessionId: 1,
);

ServiceCall _call(int id, ServiceCallType type) => ServiceCall(
  id: id,
  sessionId: 1,
  tableCode: 'A0$id',
  type: type,
  status: ServiceCallStatus.open,
  version: 0,
);

Future<void> _pump(
  WidgetTester tester, {
  required Widget home,
  required List<Override> overrides,
  UserRole role = UserRole.staff,
  Set<Permission> permissions = const <Permission>{},
}) async {
  tester.view.physicalSize = const Size(1200, 900);
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

void main() {
  group('Màn Đơn hàng', () {
    List<Override> base(Future<List<OrderBatch>> Function() loader) =>
        <Override>[
          pendingBatchesProvider.overrideWith(() => _PendingFake(loader)),
          readyItemsProvider.overrideWith(_ReadyFake.new),
          cancelRequestsProvider.overrideWith(_CancelFake.new),
          serviceCallsProvider.overrideWith(
            () => _CallsFake(() async => const <ServiceCall>[]),
          ),
        ];

    testWidgets('Loading: hiện skeleton', (tester) async {
      final never = Completer<List<OrderBatch>>();
      await _pump(
        tester,
        home: const OrdersScreen(),
        overrides: base(() => never.future),
      );
      await tester.pump();
      expect(find.byType(SkeletonBox), findsWidgets);
    });

    testWidgets('Empty: báo chưa có đợt nào', (tester) async {
      await _pump(
        tester,
        home: const OrdersScreen(),
        overrides: base(() async => const <OrderBatch>[]),
      );
      await tester.pump();
      await tester.pump();
      expect(find.text('Không có đợt nào chờ xác nhận'), findsOneWidget);
    });

    testWidgets('Success: hiện đợt chờ và số lượng trên tab', (tester) async {
      await _pump(
        tester,
        home: const OrdersScreen(),
        overrides: base(
          () async => <OrderBatch>[_batch(1), _batch(2, table: 'B02')],
        ),
      );
      await tester.pump();
      await tester.pump();
      expect(find.text('A01'), findsOneWidget);
      expect(find.text('B02'), findsOneWidget);
      expect(find.text('Chờ xác nhận (2)'), findsOneWidget);
    });

    testWidgets('Error: hiện thông báo lỗi', (tester) async {
      await _pump(
        tester,
        home: const OrdersScreen(),
        overrides: base(() async => throw ApiException.unexpected()),
      );
      await tester.pump();
      await tester.pump();
      expect(find.byType(ErrorView), findsOneWidget);
    });

    testWidgets(
      'STAFF không có quyền duyệt hủy thì không thấy tab Yêu cầu hủy',
      (tester) async {
        await _pump(
          tester,
          home: const OrdersScreen(),
          overrides: base(() async => const <OrderBatch>[]),
        );
        await tester.pump();
        expect(find.textContaining('Yêu cầu hủy'), findsNothing);
        expect(find.textContaining('Hỗ trợ'), findsWidgets);
      },
    );

    testWidgets('MANAGER thấy tab Yêu cầu hủy', (tester) async {
      await _pump(
        tester,
        home: const OrdersScreen(),
        role: UserRole.manager,
        overrides: base(() async => const <OrderBatch>[]),
      );
      await tester.pump();
      expect(find.textContaining('Yêu cầu hủy'), findsWidgets);
    });
  });

  group('Tab Hỗ trợ', () {
    testWidgets('Empty: báo không có yêu cầu', (tester) async {
      await _pump(
        tester,
        home: const ServiceCallsTab(),
        overrides: <Override>[
          serviceCallsProvider.overrideWith(
            () => _CallsFake(() async => const <ServiceCall>[]),
          ),
        ],
      );
      await tester.pump();
      await tester.pump();
      expect(find.text('Không có yêu cầu hỗ trợ'), findsOneWidget);
    });

    testWidgets('Nhận xử lý gọi đúng API', (tester) async {
      final repo = FakeServiceCallRepository();
      await _pump(
        tester,
        home: const ServiceCallsTab(),
        overrides: <Override>[
          serviceCallRepositoryProvider.overrideWithValue(repo),
          serviceCallsProvider.overrideWith(
            () => _CallsFake(
              () async => <ServiceCall>[_call(1, ServiceCallType.callStaff)],
            ),
          ),
        ],
      );
      await tester.pump();
      await tester.pump();
      await tester.tap(find.text('Nhận xử lý'));
      await tester.pump();
      await tester.pump();
      expect(repo.calls, contains('ack:1'));
    });

    testWidgets('STAFF không xử lý được yêu cầu dành cho Manager', (
      tester,
    ) async {
      await _pump(
        tester,
        home: const ServiceCallsTab(),
        overrides: <Override>[
          serviceCallsProvider.overrideWith(
            () => _CallsFake(
              () async => <ServiceCall>[
                _call(1, ServiceCallType.managerSupport),
              ],
            ),
          ),
        ],
      );
      await tester.pump();
      await tester.pump();
      expect(find.text('Chỉ Manager xử lý yêu cầu này.'), findsOneWidget);
      expect(find.text('Nhận xử lý'), findsNothing);
    });

    testWidgets('MANAGER xử lý được yêu cầu dành cho Manager', (tester) async {
      await _pump(
        tester,
        home: const ServiceCallsTab(),
        role: UserRole.manager,
        overrides: <Override>[
          serviceCallsProvider.overrideWith(
            () => _CallsFake(
              () async => <ServiceCall>[
                _call(1, ServiceCallType.managerSupport),
              ],
            ),
          ),
        ],
      );
      await tester.pump();
      await tester.pump();
      expect(find.text('Nhận xử lý'), findsOneWidget);
    });
  });

  group('Màn Bếp (KDS)', () {
    testWidgets('Empty: chưa có món', (tester) async {
      await _pump(
        tester,
        home: const KitchenScreen(),
        role: UserRole.kitchen,
        overrides: <Override>[
          kitchenItemsProvider.overrideWith(
            () => _KitchenFake(() async => const <KitchenItem>[]),
          ),
        ],
      );
      await tester.pump();
      await tester.pump();
      expect(find.byType(EmptyView), findsWidgets);
    });

    testWidgets('Bắt đầu nấu gửi PREPARING; món đang nấu có nút Xong', (
      tester,
    ) async {
      final repo = FakeOrdersRepository();
      await _pump(
        tester,
        home: const KitchenScreen(),
        role: UserRole.kitchen,
        overrides: <Override>[
          ordersRepositoryProvider.overrideWithValue(repo),
          kitchenItemsProvider.overrideWith(
            () => _KitchenFake(
              () async => <KitchenItem>[
                _kitchenItem(1, OrderItemStatus.confirmed),
                _kitchenItem(2, OrderItemStatus.preparing),
              ],
            ),
          ),
        ],
      );
      await tester.pump();
      await tester.pump();
      expect(find.text('Xong, sẵn sàng'), findsOneWidget);
      await tester.tap(find.text('Bắt đầu nấu'));
      await tester.pump();
      await tester.pump();
      expect(repo.calls, contains('advance:1:PREPARING'));
    });

    testWidgets('Xong, sẵn sàng gửi READY', (tester) async {
      final repo = FakeOrdersRepository();
      await _pump(
        tester,
        home: const KitchenScreen(),
        role: UserRole.kitchen,
        overrides: <Override>[
          ordersRepositoryProvider.overrideWithValue(repo),
          kitchenItemsProvider.overrideWith(
            () => _KitchenFake(
              () async => <KitchenItem>[
                _kitchenItem(2, OrderItemStatus.preparing),
              ],
            ),
          ),
        ],
      );
      await tester.pump();
      await tester.pump();
      await tester.tap(find.text('Xong, sẵn sàng'));
      await tester.pump();
      await tester.pump();
      expect(repo.calls, contains('advance:2:READY'));
    });
  });

  group('Thu ngân: danh sách bàn', () {
    FloorPlanTable table(int id, TableStatus status, {bool session = true}) {
      final base = fakeFloorTable(id, status: status);
      if (!session) return base;
      return FloorPlanTable(
        table: base.table,
        hasActiveQr: true,
        activeSession: ActiveSessionSummary(
          id: id,
          status: SessionStatus.open,
          lastActivityAt: DateTime(2026, 11, 24),
          needsAttention: false,
          deviceCount: 1,
          version: 0,
        ),
      );
    }

    test('chỉ lấy bàn có phiên, chờ thanh toán lên trước', () {
      final result = cashierTables(<FloorPlanTable>[
        table(1, TableStatus.occupied),
        table(2, TableStatus.paymentPending),
        table(3, TableStatus.available, session: false),
        table(4, TableStatus.cleaning),
      ]);
      expect(result.map((t) => t.id).toList(), equals(<int>[2, 1]));
    });
  });

  group('Gọi món hộ', () {
    final categories = <MenuCategory>[
      const MenuCategory(
        id: 1,
        name: 'Món chính',
        displayOrder: 1,
        visible: true,
      ),
    ];
    final products = <MenuProduct>[
      const MenuProduct(
        id: 11,
        categoryId: 1,
        name: 'Phở bò',
        price: 50000,
        available: true,
        optionGroups: <AssignedOptionGroup>[],
      ),
    ];

    Future<FakeOrdersRepository> open(WidgetTester tester) async {
      final orders = FakeOrdersRepository();
      await _pump(
        tester,
        home: const PlaceOrderView(sessionId: 9, tableCode: 'A01'),
        overrides: <Override>[
          ordersRepositoryProvider.overrideWithValue(orders),
          menuRepositoryProvider.overrideWithValue(
            FakeMenuRepository(categories, products),
          ),
        ],
      );
      await tester.pump();
      await tester.pump();
      return orders;
    }

    testWidgets('thêm món vào giỏ và gửi đợt đúng nội dung', (tester) async {
      final orders = await open(tester);
      expect(find.text('Chưa chọn món'), findsOneWidget);
      await tester.tap(find.text('Phở bò'));
      await tester.pump();
      await tester.pump();
      await tester.pump();
      expect(find.textContaining('Tạm tính 50.000'), findsOneWidget);

      await tester.tap(find.textContaining('Gửi đợt'));
      await tester.pump();
      await tester.pump();
      expect(orders.placed, hasLength(1));
      expect(orders.placed.first['sessionId'], equals(9));
      final items = orders.placed.first['items'] as List<Map<String, dynamic>>;
      expect(items.first['productId'], equals(11));
      expect(items.first['quantity'], equals(1));
    });

    testWidgets('gửi lại cùng giỏ sau lỗi giữ nguyên clientRequestId', (
      tester,
    ) async {
      final orders = await open(tester);
      orders.placeError = ApiException.unexpected();
      await tester.tap(find.text('Phở bò'));
      await tester.pump();
      await tester.pump();
      await tester.pump();

      await tester.tap(find.textContaining('Gửi đợt'));
      await tester.pump();
      await tester.pump();
      await tester.tap(find.textContaining('Gửi đợt'));
      await tester.pump();
      await tester.pump();

      expect(orders.placed, hasLength(2));
      expect(
        orders.placed[0]['clientRequestId'],
        equals(orders.placed[1]['clientRequestId']),
      );
    });

    testWidgets('đổi giỏ thì sinh clientRequestId mới', (tester) async {
      final orders = await open(tester);
      orders.placeError = ApiException.unexpected();
      await tester.tap(find.text('Phở bò'));
      await tester.pump();
      await tester.pump();
      await tester.pump();
      await tester.tap(find.textContaining('Gửi đợt'));
      await tester.pump();
      await tester.pump();

      await tester.tap(find.byTooltip('Tăng'));
      await tester.pump();
      await tester.tap(find.textContaining('Gửi đợt'));
      await tester.pump();
      await tester.pump();

      expect(orders.placed, hasLength(2));
      expect(
        orders.placed[0]['clientRequestId'] ==
            orders.placed[1]['clientRequestId'],
        isFalse,
      );
    });
  });

  group('Sửa / hủy món', () {
    OrderItem item() => const OrderItem(
      id: 5,
      batchId: 1,
      productName: 'Phở bò',
      quantity: 2,
      lineTotal: 100000,
      status: OrderItemStatus.confirmed,
      options: <OrderOption>[],
      version: 8,
    );

    testWidgets('đổi số lượng gửi kèm version của món', (tester) async {
      final repo = FakeOrdersRepository();
      await _pump(
        tester,
        home: ItemEditMenu(item: item(), sessionId: 1),
        overrides: <Override>[ordersRepositoryProvider.overrideWithValue(repo)],
      );
      await tester.tap(find.byTooltip('Sửa món'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Sửa số lượng'));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Tăng'));
      await tester.pump();
      await tester.tap(find.text('Lưu'));
      await tester.pumpAndSettle();
      expect(repo.calls, contains('quantity:5:3:v8'));
    });

    testWidgets('hủy món bắt buộc nhập lý do', (tester) async {
      final repo = FakeOrdersRepository();
      await _pump(
        tester,
        home: ItemEditMenu(item: item(), sessionId: 1),
        overrides: <Override>[ordersRepositoryProvider.overrideWithValue(repo)],
      );
      await tester.tap(find.byTooltip('Sửa món'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Hủy món'));
      await tester.pumpAndSettle();
      // Bỏ trống lý do: không được gọi API.
      await tester.tap(find.widgetWithText(FilledButton, 'Hủy món'));
      await tester.pump();
      expect(repo.calls, isEmpty);

      await tester.enterText(find.byType(TextField), 'Khách đổi ý');
      await tester.tap(find.widgetWithText(FilledButton, 'Hủy món'));
      await tester.pumpAndSettle();
      expect(repo.calls, contains('cancel:5:Khách đổi ý'));
    });
  });
}
