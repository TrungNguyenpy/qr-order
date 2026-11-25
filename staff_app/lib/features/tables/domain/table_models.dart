import '../../../core/util/json_utils.dart';

/// Trạng thái bàn (backend `TableStatus`, DEC-04 B).
enum TableStatus {
  available('AVAILABLE', 'Trống'),
  occupied('OCCUPIED', 'Đang phục vụ'),
  paymentPending('PAYMENT_PENDING', 'Chờ thanh toán'),
  cleaning('CLEANING', 'Đang dọn');

  const TableStatus(this.apiName, this.label);

  final String apiName;
  final String label;

  static TableStatus? parse(Object? value) {
    final text = asStringOrNull(value);
    for (final status in TableStatus.values) {
      if (status.apiName == text) return status;
    }
    return null;
  }
}

/// Trạng thái phiên bàn (backend `SessionStatus`).
enum SessionStatus {
  open('OPEN', 'Đang mở'),
  billing('BILLING', 'Đang tính tiền'),
  closed('CLOSED', 'Đã đóng'),
  expired('EXPIRED', 'Hết hạn'),
  forceClosed('FORCE_CLOSED', 'Đóng cưỡng bức');

  const SessionStatus(this.apiName, this.label);

  final String apiName;
  final String label;

  bool get isActive => this == open || this == billing;

  static SessionStatus? parse(Object? value) {
    final text = asStringOrNull(value);
    for (final status in SessionStatus.values) {
      if (status.apiName == text) return status;
    }
    return null;
  }
}

/// Bàn (backend `TableResponse`).
class DiningTable {
  const DiningTable({
    required this.id,
    required this.code,
    required this.displayName,
    required this.area,
    required this.capacity,
    required this.status,
    required this.active,
    required this.displayOrder,
    required this.version,
    this.statusChangedAt,
  });

  factory DiningTable.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    final status = TableStatus.parse(json['status']);
    if (id == null || status == null) {
      throw const FormatException('Dữ liệu bàn không hợp lệ.');
    }
    return DiningTable(
      id: id,
      code: asString(json['code']),
      displayName: asString(json['displayName']),
      area: asStringOrNull(json['area']),
      capacity: asInt(json['capacity']),
      status: status,
      active: asBool(json['active'], fallback: true),
      displayOrder: asInt(json['displayOrder']),
      version: asInt(json['version']),
      statusChangedAt: asDateTime(json['statusChangedAt']),
    );
  }

  final int id;
  final String code;
  final String displayName;
  final String? area;
  final int capacity;
  final TableStatus status;
  final bool active;
  final int displayOrder;
  final int version;
  final DateTime? statusChangedAt;

  DiningTable copyWith({TableStatus? status, bool? active, int? version}) =>
      DiningTable(
        id: id,
        code: code,
        displayName: displayName,
        area: area,
        capacity: capacity,
        status: status ?? this.status,
        active: active ?? this.active,
        displayOrder: displayOrder,
        version: version ?? this.version,
        statusChangedAt: statusChangedAt,
      );
}

/// Phiên đang hoạt động trên sơ đồ bàn (backend `ActiveSessionSummary`).
class ActiveSessionSummary {
  const ActiveSessionSummary({
    required this.id,
    required this.status,
    required this.lastActivityAt,
    required this.needsAttention,
    required this.deviceCount,
    required this.version,
    this.openedAt,
  });

  factory ActiveSessionSummary.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    final status = SessionStatus.parse(json['status']);
    if (id == null || status == null) {
      throw const FormatException('Dữ liệu phiên không hợp lệ.');
    }
    return ActiveSessionSummary(
      id: id,
      status: status,
      openedAt: asDateTime(json['openedAt']),
      lastActivityAt:
          asDateTime(json['lastActivityAt']) ??
          DateTime.fromMillisecondsSinceEpoch(0, isUtc: true),
      needsAttention: asBool(json['needsAttention']),
      deviceCount: asInt(json['deviceCount']),
      version: asInt(json['version']),
    );
  }

  final int id;
  final SessionStatus status;
  final DateTime? openedAt;
  final DateTime lastActivityAt;
  final bool needsAttention;
  final int deviceCount;
  final int version;
}

/// Một ô trên sơ đồ bàn (backend `FloorPlanTableResponse`).
class FloorPlanTable {
  const FloorPlanTable({
    required this.table,
    required this.hasActiveQr,
    this.activeSession,
  });

  factory FloorPlanTable.fromJson(Map<String, dynamic> json) {
    final session = json['activeSession'];
    return FloorPlanTable(
      table: DiningTable.fromJson(asMap(json['table'])),
      hasActiveQr: asBool(json['hasActiveQr']),
      activeSession: session is Map
          ? ActiveSessionSummary.fromJson(asMap(session))
          : null,
    );
  }

  final DiningTable table;
  final bool hasActiveQr;
  final ActiveSessionSummary? activeSession;

  int get id => table.id;

  FloorPlanTable copyWith({DiningTable? table, bool clearSession = false}) =>
      FloorPlanTable(
        table: table ?? this.table,
        hasActiveQr: hasActiveQr,
        activeSession: clearSession ? null : activeSession,
      );
}

/// Chi tiết phiên cho nhân viên (backend `StaffSessionResponse`), gồm PIN bàn.
class StaffSession {
  const StaffSession({
    required this.id,
    required this.tableId,
    required this.status,
    required this.lastActivityAt,
    required this.needsAttention,
    required this.deviceCount,
    required this.version,
    this.tableCode,
    this.tableName,
    this.area,
    this.openedAt,
    this.verifiedAt,
    this.tablePin,
    this.billingStartedAt,
    this.closedAt,
    this.closeReason,
  });

  factory StaffSession.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    final status = SessionStatus.parse(json['status']);
    if (id == null || status == null) {
      throw const FormatException('Dữ liệu phiên không hợp lệ.');
    }
    return StaffSession(
      id: id,
      tableId: asInt(json['tableId']),
      tableCode: asStringOrNull(json['tableCode']),
      tableName: asStringOrNull(json['tableName']),
      area: asStringOrNull(json['area']),
      status: status,
      openedAt: asDateTime(json['openedAt']),
      lastActivityAt:
          asDateTime(json['lastActivityAt']) ??
          DateTime.fromMillisecondsSinceEpoch(0, isUtc: true),
      verifiedAt: asDateTime(json['verifiedAt']),
      tablePin: asStringOrNull(json['tablePin']),
      needsAttention: asBool(json['needsAttention']),
      billingStartedAt: asDateTime(json['billingStartedAt']),
      closedAt: asDateTime(json['closedAt']),
      closeReason: asStringOrNull(json['closeReason']),
      deviceCount: asInt(json['deviceCount']),
      version: asInt(json['version']),
    );
  }

  final int id;
  final int tableId;
  final String? tableCode;
  final String? tableName;
  final String? area;
  final SessionStatus status;
  final DateTime? openedAt;
  final DateTime lastActivityAt;
  final DateTime? verifiedAt;
  final String? tablePin;
  final bool needsAttention;
  final DateTime? billingStartedAt;
  final DateTime? closedAt;
  final String? closeReason;
  final int deviceCount;
  final int version;

  /// Phiên đã được nhân viên xác nhận đợt đầu (BR-S04).
  bool get isVerified => verifiedAt != null;

  bool get hasPin => (tablePin ?? '').isNotEmpty;
}
