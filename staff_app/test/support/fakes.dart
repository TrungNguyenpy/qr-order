import 'dart:async';

import 'package:staff_app/core/network/api_exception.dart';
import 'package:staff_app/core/realtime/realtime_message.dart';
import 'package:staff_app/core/realtime/stomp_realtime_service.dart';
import 'package:staff_app/features/auth/application/session_controller.dart';
import 'package:staff_app/features/auth/data/auth_repository.dart';
import 'package:staff_app/features/auth/domain/auth_models.dart';
import 'package:staff_app/features/billing/data/billing_repository.dart';
import 'package:staff_app/features/billing/domain/billing_models.dart';
import 'package:staff_app/features/menu/data/menu_repository.dart';
import 'package:staff_app/features/menu/domain/menu_models.dart';
import 'package:staff_app/features/orders/data/orders_repository.dart';
import 'package:staff_app/features/orders/domain/order_models.dart';
import 'package:staff_app/features/support/data/service_call_repository.dart';
import 'package:staff_app/features/support/domain/service_call_models.dart';
import 'package:staff_app/features/tables/data/tables_repository.dart';
import 'package:staff_app/features/tables/domain/table_models.dart';

StaffUser fakeUser({
  UserRole role = UserRole.staff,
  Set<Permission> permissions = const <Permission>{},
}) => StaffUser(
  id: 1,
  username: 'staff1',
  fullName: 'Nguyễn Văn A',
  role: role,
  status: 'ACTIVE',
  permissions: permissions,
);

/// Kho đăng nhập giả: kịch bản do từng test đặt.
class FakeAuthRepository implements AuthRepository {
  StaffUser? meResult;
  ApiException? meError;
  StaffUser? loginResult;
  ApiException? loginError;
  ApiException? changePasswordError;
  final List<List<String>> loginCalls = <List<String>>[];
  int logoutCalls = 0;
  int changePasswordCalls = 0;

  @override
  Future<StaffUser> login(String username, String password) async {
    loginCalls.add(<String>[username, password]);
    final error = loginError;
    if (error != null) throw error;
    return loginResult ?? fakeUser();
  }

  @override
  Future<StaffUser> me() async {
    final error = meError;
    if (error != null) throw error;
    return meResult ?? fakeUser();
  }

  @override
  Future<void> logout() async {
    logoutCalls++;
  }

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    changePasswordCalls++;
    final error = changePasswordError;
    if (error != null) throw error;
  }

  @override
  Future<void> ping() async {}
}

/// STOMP giả: ghi lại start/stop, không mở socket.
class FakeRealtimeService implements StompRealtimeService {
  final List<UserRole> started = <UserRole>[];
  int stopCalls = 0;
  final StreamController<RealtimeMessage> _messages =
      StreamController<RealtimeMessage>.broadcast();
  final StreamController<void> _connections =
      StreamController<void>.broadcast();
  final StreamController<RealtimeStatus> _statuses =
      StreamController<RealtimeStatus>.broadcast();

  @override
  RealtimeStatus status = RealtimeStatus.disconnected;

  @override
  Stream<RealtimeMessage> get messages => _messages.stream;

  @override
  Stream<void> get connections => _connections.stream;

  @override
  Stream<RealtimeStatus> get statuses => _statuses.stream;

  void emit(RealtimeMessage message) => _messages.add(message);

  @override
  void start(UserRole role) => started.add(role);

  @override
  void stop() => stopCalls++;

  @override
  Future<void> dispose() async {
    await _messages.close();
    await _connections.close();
    await _statuses.close();
  }
}

/// Kho bàn giả.
class FakeTablesRepository implements TablesRepository {
  final List<List<int>> cleanedCalls = <List<int>>[]; // [tableId, version]
  ApiException? cleanError;

  /// Đợt 4: `sessionId:reason:version` của mỗi lần đóng cưỡng bức.
  final List<String> forceCloseCalls = <String>[];
  ApiException? forceCloseError;

  /// Phiên kết thúc gần nhất trả về cho `latestEndedSession`.
  StaffSession? endedSession;
  ApiException? endedSessionError;

  @override
  Future<List<FloorPlanTable>> floorPlan() async => const <FloorPlanTable>[];

  @override
  Future<StaffSession> forceClose(
    int sessionId, {
    required String reason,
    int? version,
  }) async {
    forceCloseCalls.add('$sessionId:$reason:${version ?? ''}');
    final error = forceCloseError;
    if (error != null) throw error;
    return StaffSession(
      id: sessionId,
      tableId: 1,
      status: SessionStatus.forceClosed,
      lastActivityAt: DateTime.utc(2026, 11, 25),
      needsAttention: false,
      deviceCount: 0,
      version: (version ?? 0) + 1,
      closeReason: reason,
    );
  }

  @override
  Future<StaffSession?> latestEndedSession(int tableId) async {
    final error = endedSessionError;
    if (error != null) throw error;
    return endedSession;
  }

  /// Chi tiết phiên trả về cho `session`; mặc định không dùng (ném lỗi).
  StaffSession? sessionResult;

  @override
  Future<StaffSession> session(int sessionId) async =>
      sessionResult ?? (throw UnimplementedError('không dùng trong test này'));

  @override
  Future<DiningTable> confirmCleaned({
    required int tableId,
    required int version,
  }) async {
    cleanedCalls.add(<int>[tableId, version]);
    final error = cleanError;
    if (error != null) throw error;
    return DiningTable(
      id: tableId,
      code: 'B',
      displayName: 'B',
      area: null,
      capacity: 4,
      status: TableStatus.available,
      active: true,
      displayOrder: 1,
      version: version + 1,
    );
  }
}

/// Dựng một ô bàn cho test giao diện.
FloorPlanTable fakeFloorTable(
  int id, {
  String? code,
  String area = 'Tầng 1',
  TableStatus status = TableStatus.available,
  int version = 1,
}) {
  return FloorPlanTable(
    table: DiningTable(
      id: id,
      code: code ?? 'B0$id',
      displayName: 'Bàn $id',
      area: area,
      capacity: 4,
      status: status,
      active: true,
      displayOrder: id,
      version: version,
    ),
    hasActiveQr: true,
  );
}

// ---------------------------------------------------------------------------
// Đợt 2/3: kho giả cho đơn hàng, hỗ trợ, thực đơn.
// ---------------------------------------------------------------------------

/// Bọc [user] thành phiên đã đăng nhập, dùng với `sessionControllerProvider.overrideWith`.
class FixedSessionController extends SessionController {
  FixedSessionController(this.user);

  final StaffUser user;

  @override
  AuthState build() => AuthState(status: AuthStatus.authenticated, user: user);
}

/// Kho đơn hàng giả: ghi lại lời gọi, lỗi do từng test đặt.
class FakeOrdersRepository implements OrdersRepository {
  final List<String> calls = <String>[];
  final List<Map<String, dynamic>> placed = <Map<String, dynamic>>[];
  ApiException? placeError;
  ApiException? actionError;

  Future<void> _record(String call) async {
    calls.add(call);
    final error = actionError;
    if (error != null) throw error;
  }

  @override
  Future<void> advanceKitchenItem(int itemId, OrderItemStatus target) =>
      _record('advance:$itemId:${target.apiName}');

  /// Order trả về cho `sessionOrder` (Đợt 4: màn kết quả đóng phiên, thu ngân).
  SessionOrder? sessionOrderResult;

  @override
  Future<SessionOrder> sessionOrder(int sessionId) async =>
      sessionOrderResult ??
      SessionOrder(sessionId: sessionId, batches: const <OrderBatch>[], billableTotal: 0);

  @override
  Future<void> serveItem(int itemId) => _record('serve:$itemId');

  @override
  Future<void> changeQuantity(
    int itemId, {
    required int quantity,
    required int version,
  }) => _record('quantity:$itemId:$quantity:v$version');

  @override
  Future<void> cancelItem(int itemId, {required String reason}) =>
      _record('cancel:$itemId:$reason');

  @override
  Future<PlacedBatch> placeForGuest(
    int sessionId, {
    required String clientRequestId,
    required List<Map<String, dynamic>> items,
  }) async {
    placed.add(<String, dynamic>{
      'sessionId': sessionId,
      'clientRequestId': clientRequestId,
      'items': items,
    });
    final error = placeError;
    if (error != null) throw error;
    return PlacedBatch(
      replayed: false,
      batch: const OrderBatch(
        id: 1,
        seq: 1,
        status: BatchStatus.pending,
        items: <OrderItem>[],
        version: 0,
      ),
      billableTotal: 0,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError(
    '${invocation.memberName} không dùng trong test',
  );
}

/// Kho yêu cầu hỗ trợ giả.
class FakeServiceCallRepository implements ServiceCallRepository {
  final List<String> calls = <String>[];

  ServiceCall _call(int id) => ServiceCall(
    id: id,
    sessionId: 1,
    type: ServiceCallType.callStaff,
    status: ServiceCallStatus.acknowledged,
    version: 1,
  );

  @override
  Future<ServiceCall> acknowledge(int id) async {
    calls.add('ack:$id');
    return _call(id);
  }

  @override
  Future<ServiceCall> resolve(int id, {String? note}) async {
    calls.add('resolve:$id:${note ?? ''}');
    return _call(id);
  }

  @override
  Future<ServiceCall> requestManager(int sessionId, {String? note}) async {
    calls.add('manager:$sessionId:${note ?? ''}');
    return _call(1);
  }

  @override
  Future<List<ServiceCall>> queue() async => const <ServiceCall>[];
}

/// Thực đơn giả: các món không có option.
class FakeMenuRepository implements MenuRepository {
  FakeMenuRepository(this.categoriesData, this.productsData);

  final List<MenuCategory> categoriesData;
  final List<MenuProduct> productsData;

  @override
  Future<List<MenuCategory>> categories() async => categoriesData;

  @override
  Future<List<MenuProduct>> availableProducts() async => productsData;

  @override
  Future<MenuProduct> product(int id) async =>
      productsData.firstWhere((p) => p.id == id);

  @override
  Future<List<OptionGroupDetail>> optionGroupsOf(MenuProduct product) async =>
      const <OptionGroupDetail>[];

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError(
    '${invocation.memberName} không dùng trong test',
  );
}

/// Kho thu ngân giả cho Đợt 4: biên lai hoàn tiền, lịch sử hoàn, Bill hiện hành.
class FakeBillingRepository implements BillingRepository {
  RefundReceipt? refundReceiptResult;
  ApiException? receiptError;

  /// Giữ lời gọi biên lai ở trạng thái đang tải cho tới khi hoàn tất (test Loading).
  Completer<void>? receiptGate;
  List<Refund> refundList = <Refund>[];
  ApiException? refundsError;
  Bill? bill;
  final List<int> receiptCalls = <int>[];

  @override
  Future<RefundReceipt> refundReceipt(int refundId) async {
    receiptCalls.add(refundId);
    final gate = receiptGate;
    if (gate != null) await gate.future;
    final error = receiptError;
    if (error != null) throw error;
    return refundReceiptResult!;
  }

  @override
  Future<List<Refund>> refunds(int billId) async {
    final error = refundsError;
    if (error != null) throw error;
    return refundList;
  }

  @override
  Future<Bill?> latestBill(int orderId) async => bill;

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError(
    '${invocation.memberName} không dùng trong test',
  );
}
