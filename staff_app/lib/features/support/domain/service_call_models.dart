import '../../../core/util/json_utils.dart';

/// Loại yêu cầu hỗ trợ (backend `ServiceCallType`).
enum ServiceCallType {
  callStaff('CALL_STAFF', 'Khách gọi nhân viên'),
  requestPayment('REQUEST_PAYMENT', 'Khách xin thanh toán'),
  managerSupport('MANAGER_SUPPORT', 'Nhân viên cần Manager');

  const ServiceCallType(this.apiName, this.label);

  final String apiName;
  final String label;

  static ServiceCallType? parse(Object? raw) {
    final text = raw?.toString();
    for (final value in values) {
      if (value.apiName == text) return value;
    }
    return null;
  }
}

/// Trạng thái yêu cầu: OPEN → ACKNOWLEDGED → RESOLVED.
enum ServiceCallStatus {
  open('OPEN', 'Mới'),
  acknowledged('ACKNOWLEDGED', 'Đã nhận'),
  resolved('RESOLVED', 'Đã xong');

  const ServiceCallStatus(this.apiName, this.label);

  final String apiName;
  final String label;

  static ServiceCallStatus? parse(Object? raw) {
    final text = raw?.toString();
    for (final value in values) {
      if (value.apiName == text) return value;
    }
    return null;
  }
}

/// Yêu cầu hỗ trợ cho nhân viên (backend `ServiceCallResponse`).
class ServiceCall {
  const ServiceCall({
    required this.id,
    required this.sessionId,
    required this.type,
    required this.status,
    required this.version,
    this.tableCode,
    this.note,
    this.createdAt,
    this.acknowledgedBy,
    this.autoResolved = false,
  });

  factory ServiceCall.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    final type = ServiceCallType.parse(json['type']);
    final status = ServiceCallStatus.parse(json['status']);
    if (id == null || type == null || status == null) {
      throw const FormatException('Dữ liệu yêu cầu hỗ trợ không hợp lệ.');
    }
    return ServiceCall(
      id: id,
      sessionId: asInt(json['sessionId']),
      tableCode: asStringOrNull(json['tableCode']),
      type: type,
      status: status,
      note: asStringOrNull(json['note']),
      createdAt: asDateTime(json['createdAt']),
      acknowledgedBy: asIntOrNull(json['acknowledgedBy']),
      autoResolved: asBool(json['autoResolved']),
      version: asInt(json['version']),
    );
  }

  final int id;
  final int sessionId;
  final String? tableCode;
  final ServiceCallType type;
  final ServiceCallStatus status;
  final String? note;
  final DateTime? createdAt;
  final int? acknowledgedBy;
  final bool autoResolved;
  final int version;

  /// Yêu cầu dành riêng cho Manager (STAFF không được nhận/hoàn tất).
  bool get managerOnly => type == ServiceCallType.managerSupport;

  /// Người dùng hiện tại có được nhận/hoàn tất yêu cầu này không.
  bool canHandle({required bool isManager}) => !managerOnly || isManager;
}
