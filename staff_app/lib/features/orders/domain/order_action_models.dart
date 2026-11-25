import '../../../core/util/json_utils.dart';
import 'order_models.dart';

/// Một dòng trên màn hình bếp (backend `KitchenItemResponse`).
class KitchenItem {
  const KitchenItem({
    required this.itemId,
    required this.orderId,
    required this.batchId,
    required this.sessionId,
    required this.tableCode,
    required this.productName,
    required this.quantity,
    required this.status,
    required this.options,
    required this.waitingSeconds,
    required this.version,
    this.note,
    this.confirmedAt,
    this.preparingAt,
    this.readyAt,
  });

  factory KitchenItem.fromJson(Map<String, dynamic> json) {
    final itemId = asIntOrNull(json['itemId']);
    final status = OrderItemStatus.parse(json['status']);
    if (itemId == null || status == null) {
      throw const FormatException('Dữ liệu món bếp không hợp lệ.');
    }
    return KitchenItem(
      itemId: itemId,
      orderId: asInt(json['orderId']),
      batchId: asInt(json['batchId']),
      sessionId: asInt(json['sessionId']),
      tableCode: asString(json['tableCode']),
      productName: asString(json['productName']),
      quantity: asInt(json['quantity']),
      note: asStringOrNull(json['note']),
      options: asMapList(json['options']).map(OrderOption.fromJson).toList(),
      status: status,
      confirmedAt: asDateTime(json['confirmedAt']),
      preparingAt: asDateTime(json['preparingAt']),
      readyAt: asDateTime(json['readyAt']),
      waitingSeconds: asInt(json['waitingSeconds']),
      version: asInt(json['version']),
    );
  }

  final int itemId;
  final int orderId;
  final int batchId;
  final int sessionId;
  final String tableCode;
  final String productName;
  final int quantity;
  final String? note;
  final List<OrderOption> options;
  final OrderItemStatus status;
  final DateTime? confirmedAt;
  final DateTime? preparingAt;
  final DateTime? readyAt;
  final int waitingSeconds;
  final int version;

  String get optionSummary => options.map((o) => o.optionName).join(', ');
}

enum CancelRequestStatus {
  pending('PENDING'),
  approved('APPROVED'),
  denied('DENIED');

  const CancelRequestStatus(this.apiName);

  final String apiName;

  static CancelRequestStatus? parse(Object? value) {
    final text = asStringOrNull(value);
    for (final status in CancelRequestStatus.values) {
      if (status.apiName == text) return status;
    }
    return null;
  }
}

/// Yêu cầu hủy món đang nấu chờ duyệt (backend `CancelRequestResponse`).
class CancelRequest {
  const CancelRequest({
    required this.id,
    required this.orderItemId,
    required this.sessionId,
    required this.tableCode,
    required this.productName,
    required this.quantity,
    required this.itemStatusAtRequest,
    required this.currentItemStatus,
    required this.status,
    required this.version,
    this.requestReason,
    this.requestedAt,
  });

  factory CancelRequest.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    final status = CancelRequestStatus.parse(json['status']);
    if (id == null || status == null) {
      throw const FormatException('Dữ liệu yêu cầu hủy không hợp lệ.');
    }
    return CancelRequest(
      id: id,
      orderItemId: asInt(json['orderItemId']),
      sessionId: asInt(json['sessionId']),
      tableCode: asString(json['tableCode']),
      productName: asString(json['productName']),
      quantity: asInt(json['quantity']),
      itemStatusAtRequest:
          OrderItemStatus.parse(json['itemStatusAtRequest']) ??
          OrderItemStatus.preparing,
      currentItemStatus:
          OrderItemStatus.parse(json['currentItemStatus']) ??
          OrderItemStatus.preparing,
      status: status,
      requestReason: asStringOrNull(json['requestReason']),
      requestedAt: asDateTime(json['requestedAt']),
      version: asInt(json['version']),
    );
  }

  final int id;
  final int orderItemId;
  final int sessionId;
  final String tableCode;
  final String productName;
  final int quantity;
  final OrderItemStatus itemStatusAtRequest;
  final OrderItemStatus currentItemStatus;
  final CancelRequestStatus status;
  final String? requestReason;
  final DateTime? requestedAt;
  final int version;
}

/// Món READY chờ mang ra bàn dùng chung kiểu với [KitchenItem].
typedef ReadyItem = KitchenItem;

/// Lý do chuẩn (`reasonCode` UPPER_SNAKE_CASE 2-50 ký tự, BR-O08) cho từ chối món/đợt.
class ReasonPreset {
  const ReasonPreset(this.code, this.label);

  final String code;
  final String label;

  static const List<ReasonPreset> rejection = <ReasonPreset>[
    ReasonPreset('OUT_OF_STOCK', 'Hết món'),
    ReasonPreset('KITCHEN_OVERLOAD', 'Bếp quá tải'),
    ReasonPreset('INVALID_ORDER', 'Gọi sai / không hợp lệ'),
    ReasonPreset('OTHER', 'Lý do khác'),
  ];

  static final RegExp _pattern = RegExp(r'^[A-Z][A-Z0-9_]{1,49}$');

  /// Mã lý do hợp lệ theo ràng buộc của backend.
  static bool isValidCode(String code) => _pattern.hasMatch(code);
}
