import 'dart:convert';

import '../../features/auth/domain/auth_models.dart';
import '../util/json_utils.dart';

/// Kênh STOMP của backend (xem `StompTopicAuthorizer`).
class StompTopics {
  const StompTopics._();

  static const String tables = '/topic/tables';
  static const String staffOrders = '/topic/staff/orders';
  static const String staffReady = '/topic/staff/ready';
  static const String kitchenOrders = '/topic/kitchen/orders';

  static String session(int sessionId) => '/topic/session/$sessionId';
}

/// Các kênh mỗi vai trò được phép subscribe (khớp `StompTopicAuthorizer`):
/// STAFF/MANAGER: tables, staff/orders, staff/ready; KITCHEN/MANAGER: kitchen/orders.
/// ADMIN không có kênh vận hành.
List<String> topicsForRole(UserRole role) {
  switch (role) {
    case UserRole.manager:
      return const <String>[
        StompTopics.tables,
        StompTopics.staffOrders,
        StompTopics.staffReady,
        StompTopics.kitchenOrders,
      ];
    case UserRole.staff:
      return const <String>[
        StompTopics.tables,
        StompTopics.staffOrders,
        StompTopics.staffReady,
      ];
    case UserRole.kitchen:
      return const <String>[StompTopics.kitchenOrders];
    case UserRole.admin:
      return const <String>[];
  }
}

/// Một khung tin STOMP đã giải mã JSON. Sự kiện chỉ là tín hiệu làm mới: dữ liệu
/// chuẩn luôn lấy lại bằng REST (hợp đồng backend).
class RealtimeMessage {
  const RealtimeMessage({required this.destination, required this.payload});

  /// Trả về null nếu thân tin không phải JSON object.
  static RealtimeMessage? tryParse(String destination, String? body) {
    if (body == null || body.isEmpty) return null;
    try {
      final decoded = jsonDecode(body);
      if (decoded is Map) {
        return RealtimeMessage(
          destination: destination,
          payload: Map<String, dynamic>.from(decoded),
        );
      }
    } on FormatException {
      return null;
    }
    return null;
  }

  final String destination;
  final Map<String, dynamic> payload;

  String get type => asString(payload['type']);

  int? get eventId => asIntOrNull(payload['eventId']);

  int? get sessionId => asIntOrNull(payload['sessionId']);
}

/// Trạng thái kết nối STOMP.
enum RealtimeStatus { disconnected, connecting, connected }
