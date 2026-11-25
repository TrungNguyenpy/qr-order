import '../../../core/network/api_client.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/network/api_paths.dart';
import '../../../core/util/json_utils.dart';
import '../domain/order_action_models.dart';
import '../domain/order_models.dart';

/// Gọi API đơn hàng (STAFF/MANAGER) và bếp (KITCHEN/MANAGER).
class OrdersRepository {
  OrdersRepository(this._client);

  final ApiClient _client;

  T _parse<T>(T Function() builder) {
    try {
      return builder();
    } on FormatException {
      throw ApiException.unexpected();
    }
  }

  /// `GET /api/v1/orders/sessions/{sessionId}` — order của phiên.
  Future<SessionOrder> sessionOrder(int sessionId) async {
    final data = await _client.get(ApiPaths.sessionOrder(sessionId));
    return _parse(() => SessionOrder.fromJson(asMap(data)));
  }

  /// Đợt chờ xác nhận, cũ trước, kèm người đang giữ claim.
  Future<List<OrderBatch>> pendingBatches() async {
    final data = await _client.get(ApiPaths.pendingBatches);
    return _parse(() => asMapList(data).map(OrderBatch.fromJson).toList());
  }

  /// Nhận xử lý đợt (claim, TTL do backend cấu hình). 409 `BATCH_CLAIMED_BY_OTHER`
  /// kèm tên người đang xử lý (MANAGER giành quyền được).
  Future<OrderBatch> claimBatch(int batchId) => _batchAction(batchId, 'claim');

  /// Nhả claim của chính mình.
  Future<OrderBatch> releaseBatch(int batchId) =>
      _batchAction(batchId, 'release');

  /// Xác nhận đợt; [rejectItems] (tùy chọn) từ chối một phần món → PARTIALLY_ACCEPTED.
  Future<OrderBatch> confirmBatch(
    int batchId, {
    List<ItemRejection> rejectItems = const <ItemRejection>[],
  }) => _batchAction(
    batchId,
    'confirm',
    body: <String, dynamic>{
      'rejectItems': rejectItems.map((r) => r.toJson()).toList(),
    },
  );

  /// Từ chối cả đợt (`reasonCode` bắt buộc).
  Future<OrderBatch> rejectBatch(
    int batchId, {
    required String reasonCode,
    String? reasonNote,
  }) => _batchAction(
    batchId,
    'reject',
    body: <String, dynamic>{
      'reasonCode': reasonCode,
      if (reasonNote != null && reasonNote.trim().isNotEmpty)
        'reasonNote': reasonNote.trim(),
    },
  );

  Future<OrderBatch> _batchAction(
    int batchId,
    String action, {
    Map<String, dynamic>? body,
  }) async {
    final data = await _client.post(
      ApiPaths.batchAction(batchId, action),
      data: body,
    );
    return _parse(() => OrderBatch.fromJson(asMap(data)));
  }

  /// Món READY chờ mang ra bàn, cũ trước.
  Future<List<KitchenItem>> readyItems() async {
    final data = await _client.get(ApiPaths.readyItems);
    return _parse(() => asMapList(data).map(KitchenItem.fromJson).toList());
  }

  /// READY → SERVED.
  Future<void> serveItem(int itemId) async {
    await _client.post(ApiPaths.serveItem(itemId));
  }

  /// Yêu cầu hủy món đang nấu chờ duyệt (cần `ITEM_CANCEL_APPROVE`).
  Future<List<CancelRequest>> cancelRequests() async {
    final data = await _client.get(ApiPaths.cancelRequests);
    return _parse(() => asMapList(data).map(CancelRequest.fromJson).toList());
  }

  Future<void> approveCancel(int requestId, {String? note}) async {
    await _client.post(
      ApiPaths.cancelRequestAction(requestId, 'approve'),
      data: <String, dynamic>{
        if (note != null && note.trim().isNotEmpty) 'note': note.trim(),
      },
    );
  }

  /// Từ chối yêu cầu hủy: lý do bắt buộc.
  Future<void> denyCancel(int requestId, {required String reason}) async {
    await _client.post(
      ApiPaths.cancelRequestAction(requestId, 'deny'),
      data: <String, dynamic>{'reason': reason.trim()},
    );
  }

  /// Màn hình bếp: món chờ/đang nấu (mặc định CONFIRMED, PREPARING), cũ trước.
  Future<List<KitchenItem>> kitchenItems() async {
    final data = await _client.get(ApiPaths.kitchenItems);
    return _parse(() => asMapList(data).map(KitchenItem.fromJson).toList());
  }

  /// Bếp đổi trạng thái: CONFIRMED → PREPARING → READY.
  Future<void> advanceKitchenItem(int itemId, OrderItemStatus target) async {
    await _client.post(
      ApiPaths.kitchenItemStatus(itemId),
      data: <String, dynamic>{'status': target.apiName},
    );
  }

  /// Bếp từ chối món CONFIRMED/PREPARING (PREPARING → món bỏ phí).
  Future<void> rejectKitchenItem(
    int itemId, {
    required String reasonCode,
    String? reasonNote,
  }) async {
    await _client.post(
      ApiPaths.kitchenItemReject(itemId),
      data: <String, dynamic>{
        'reasonCode': reasonCode,
        if (reasonNote != null && reasonNote.trim().isNotEmpty)
          'reasonNote': reasonNote.trim(),
      },
    );
  }

  /// Nhân viên gọi món hộ khách cho phiên [sessionId]. [clientRequestId] (UUID) làm
  /// khóa idempotent: gửi lại cùng mã sau lỗi mạng không tạo đợt trùng.
  Future<PlacedBatch> placeForGuest(
    int sessionId, {
    required String clientRequestId,
    required List<Map<String, dynamic>> items,
  }) async {
    final data = await _client.post(
      ApiPaths.sessionBatches(sessionId),
      data: <String, dynamic>{
        'clientRequestId': clientRequestId,
        'items': items,
      },
    );
    return _parse(() => PlacedBatch.fromJson(asMap(data)));
  }

  /// Đổi số lượng món PENDING/CONFIRMED (khóa lạc quan theo [version]).
  Future<void> changeQuantity(
    int itemId, {
    required int quantity,
    required int version,
  }) async {
    await _client.patch(
      ApiPaths.itemQuantity(itemId),
      data: <String, dynamic>{'quantity': quantity, 'version': version},
    );
  }

  /// Nhân viên hủy trực tiếp món PENDING/CONFIRMED; lý do bắt buộc.
  Future<void> cancelItem(int itemId, {required String reason}) async {
    await _client.post(
      ApiPaths.itemCancel(itemId),
      data: <String, dynamic>{'reason': reason.trim()},
    );
  }
}

/// Kết quả gọi món hộ (backend `StaffPlaceOrderResponse`).
class PlacedBatch {
  const PlacedBatch({
    required this.replayed,
    required this.batch,
    required this.billableTotal,
  });

  factory PlacedBatch.fromJson(Map<String, dynamic> json) => PlacedBatch(
    replayed: asBool(json['replayed']),
    batch: OrderBatch.fromJson(asMap(json['batch'])),
    billableTotal: asInt(json['billableTotal']),
  );

  final bool replayed;
  final OrderBatch batch;
  final int billableTotal;
}

/// Một món bị từ chối khi xác nhận đợt (BR-O08: `reasonCode` bắt buộc).
class ItemRejection {
  const ItemRejection({
    required this.itemId,
    required this.reasonCode,
    this.reasonNote,
  });

  final int itemId;
  final String reasonCode;
  final String? reasonNote;

  Map<String, dynamic> toJson() => <String, dynamic>{
    'itemId': itemId,
    'reasonCode': reasonCode,
    if (reasonNote != null && reasonNote!.trim().isNotEmpty)
      'reasonNote': reasonNote!.trim(),
  };
}
