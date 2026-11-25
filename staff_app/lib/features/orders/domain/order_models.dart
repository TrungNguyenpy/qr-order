import '../../../core/util/json_utils.dart';

/// Trạng thái món (backend `OrderItemStatus`).
enum OrderItemStatus {
  pending('PENDING', 'Chờ xác nhận'),
  confirmed('CONFIRMED', 'Đã xác nhận'),
  preparing('PREPARING', 'Đang chế biến'),
  ready('READY', 'Sẵn sàng'),
  served('SERVED', 'Đã phục vụ'),
  cancelled('CANCELLED', 'Đã hủy'),
  rejected('REJECTED', 'Bị từ chối');

  const OrderItemStatus(this.apiName, this.label);

  final String apiName;
  final String label;

  bool get isFinal => this == served || this == cancelled || this == rejected;

  bool get isBillable =>
      this == confirmed || this == preparing || this == ready || this == served;

  static OrderItemStatus? parse(Object? value) {
    final text = asStringOrNull(value);
    for (final status in OrderItemStatus.values) {
      if (status.apiName == text) return status;
    }
    return null;
  }
}

/// Trạng thái đợt gọi món (backend `BatchStatus`).
enum BatchStatus {
  pending('PENDING', 'Chờ xác nhận'),
  accepted('ACCEPTED', 'Đã nhận'),
  partiallyAccepted('PARTIALLY_ACCEPTED', 'Nhận một phần'),
  rejected('REJECTED', 'Bị từ chối'),
  cancelled('CANCELLED', 'Đã hủy'),
  completed('COMPLETED', 'Hoàn tất');

  const BatchStatus(this.apiName, this.label);

  final String apiName;
  final String label;

  static BatchStatus? parse(Object? value) {
    final text = asStringOrNull(value);
    for (final status in BatchStatus.values) {
      if (status.apiName == text) return status;
    }
    return null;
  }
}

/// Trạng thái Order (backend `OrderStatus`).
enum OrderStatus {
  open('OPEN', 'Đang mở'),
  locked('LOCKED', 'Đã khóa (tính tiền)'),
  paid('PAID', 'Đã thanh toán'),
  voided('VOIDED', 'Đã hủy'),
  writtenOff('WRITTEN_OFF', 'Ghi nhận thất thu');

  const OrderStatus(this.apiName, this.label);

  final String apiName;
  final String label;

  static OrderStatus? parse(Object? value) {
    final text = asStringOrNull(value);
    for (final status in OrderStatus.values) {
      if (status.apiName == text) return status;
    }
    return null;
  }
}

/// Một lựa chọn option đã chọn của món (backend `OrderOptionResponse`).
class OrderOption {
  const OrderOption({
    required this.groupName,
    required this.optionName,
    required this.extraPrice,
  });

  factory OrderOption.fromJson(Map<String, dynamic> json) => OrderOption(
    groupName: asString(json['groupName']),
    optionName: asString(json['optionName']),
    extraPrice: asInt(json['extraPrice']),
  );

  final String groupName;
  final String optionName;
  final int extraPrice;
}

/// Một dòng món (backend `OrderItemResponse`).
class OrderItem {
  const OrderItem({
    required this.id,
    required this.batchId,
    required this.productName,
    required this.quantity,
    required this.lineTotal,
    required this.status,
    required this.options,
    required this.version,
    this.note,
    this.reasonCode,
    this.hasPendingCancelRequest = false,
    this.wasted = false,
    this.productId,
    this.unitPrice = 0,
  });

  factory OrderItem.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    final status = OrderItemStatus.parse(json['status']);
    if (id == null || status == null) {
      throw const FormatException('Dữ liệu món không hợp lệ.');
    }
    final cancelRequest = json['cancelRequest'];
    return OrderItem(
      id: id,
      batchId: asInt(json['batchId']),
      productName: asString(json['productName']),
      quantity: asInt(json['quantity']),
      lineTotal: asInt(json['lineTotal']),
      note: asStringOrNull(json['note']),
      status: status,
      reasonCode: asStringOrNull(json['reasonCode']),
      options: asMapList(json['options']).map(OrderOption.fromJson).toList(),
      hasPendingCancelRequest:
          cancelRequest is Map &&
          asString(cancelRequest['status']) == 'PENDING',
      version: asInt(json['version']),
      wasted: asBool(json['wasted']),
      productId: asIntOrNull(json['productId']),
      unitPrice: asInt(json['unitPrice']),
    );
  }

  final int id;
  final int batchId;
  final String productName;
  final int quantity;
  final int lineTotal;
  final String? note;
  final OrderItemStatus status;
  final String? reasonCode;
  final List<OrderOption> options;
  final bool hasPendingCancelRequest;
  final bool wasted;
  final int? productId;
  final int unitPrice;
  final int version;
}

/// Một đợt gọi món của phiên (backend `StaffBatchResponse`).
class OrderBatch {
  const OrderBatch({
    required this.id,
    required this.seq,
    required this.status,
    required this.items,
    required this.version,
    this.submittedAt,
    this.reasonCode,
    this.orderId,
    this.sessionId,
    this.tableId,
    this.tableCode,
    this.claimedBy,
    this.claimActive = false,
    this.claimExpiresAt,
  });

  factory OrderBatch.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    final status = BatchStatus.parse(json['status']);
    if (id == null || status == null) {
      throw const FormatException('Dữ liệu đợt gọi món không hợp lệ.');
    }
    return OrderBatch(
      id: id,
      seq: asInt(json['seq']),
      status: status,
      submittedAt: asDateTime(json['submittedAt']),
      reasonCode: asStringOrNull(json['reasonCode']),
      items: asMapList(json['items']).map(OrderItem.fromJson).toList(),
      version: asInt(json['version']),
      orderId: asIntOrNull(json['orderId']),
      sessionId: asIntOrNull(json['sessionId']),
      tableId: asIntOrNull(json['tableId']),
      tableCode: asStringOrNull(json['tableCode']),
      claimedBy: asIntOrNull(json['claimedBy']),
      claimActive: asBool(json['claimActive']),
      claimExpiresAt: asDateTime(json['claimExpiresAt']),
    );
  }

  final int id;
  final int seq;
  final BatchStatus status;
  final DateTime? submittedAt;
  final String? reasonCode;
  final List<OrderItem> items;
  final int version;
  final int? orderId;
  final int? sessionId;
  final int? tableId;
  final String? tableCode;

  /// Người đang giữ claim (chỉ có ở phản hồi nhân viên).
  final int? claimedBy;

  /// Claim còn hiệu lực (theo đồng hồ máy chủ).
  final bool claimActive;
  final DateTime? claimExpiresAt;

  /// Đợt này đang bị người khác (không phải [userId]) giữ claim còn hạn.
  bool isClaimedByOther(int userId) =>
      claimActive && claimedBy != null && claimedBy != userId;

  bool isClaimedBy(int userId) => claimActive && claimedBy == userId;

  /// Chỉ các món còn chờ xác nhận mới có thể từ chối khi xác nhận đợt.
  List<OrderItem> get pendingItems =>
      items.where((item) => item.status == OrderItemStatus.pending).toList();
}

/// Order của một phiên cho nhân viên (backend `StaffOrderResponse`).
class SessionOrder {
  const SessionOrder({
    required this.sessionId,
    required this.batches,
    required this.billableTotal,
    this.orderId,
    this.status,
  });

  factory SessionOrder.fromJson(Map<String, dynamic> json) => SessionOrder(
    orderId: asIntOrNull(json['orderId']),
    sessionId: asInt(json['sessionId']),
    status: OrderStatus.parse(json['status']),
    batches: asMapList(json['batches']).map(OrderBatch.fromJson).toList(),
    billableTotal: asInt(json['billableTotal']),
  );

  /// `orderId` null = phiên chưa có Order (khách chưa gọi món).
  final int? orderId;
  final int sessionId;
  final OrderStatus? status;
  final List<OrderBatch> batches;
  final int billableTotal;

  bool get isEmpty => batches.isEmpty;
}

/// Chỉ gọi món hộ / sửa món khi Order chưa có hoặc đang mở (LOCKED/PAID thì không thêm món).
bool canPlaceOrderIn(OrderStatus? status) =>
    status == null || status == OrderStatus.open;
