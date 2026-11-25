import '../../../core/realtime/realtime_message.dart';
import '../../../core/util/json_utils.dart';
import 'table_models.dart';

/// Bộ lọc trên sơ đồ bàn: Tất cả, Trống, Đang phục vụ, Chờ thanh toán, Đang dọn.
enum TableFilter {
  all('Tất cả', null),
  available('Trống', TableStatus.available),
  occupied('Đang phục vụ', TableStatus.occupied),
  paymentPending('Chờ thanh toán', TableStatus.paymentPending),
  cleaning('Đang dọn', TableStatus.cleaning);

  const TableFilter(this.label, this.status);

  final String label;
  final TableStatus? status;

  bool matches(FloorPlanTable item) =>
      status == null || item.table.status == status;
}

const Map<String, String> _vietnameseGroups = <String, String>{
  'a': 'àáạảãâầấậẩẫăằắặẳẵ',
  'e': 'èéẹẻẽêềếệểễ',
  'i': 'ìíịỉĩ',
  'o': 'òóọỏõôồốộổỗơờớợởỡ',
  'u': 'ùúụủũưừứựửữ',
  'y': 'ỳýỵỷỹ',
  'd': 'đ',
};

final Map<int, String> _vietnameseFold = <int, String>{
  for (final entry in _vietnameseGroups.entries)
    for (final rune in entry.value.runes) rune: entry.key,
};

/// Bỏ dấu tiếng Việt thô để tìm kiếm không phân biệt dấu.
String foldVietnamese(String input) {
  final buffer = StringBuffer();
  for (final rune in input.toLowerCase().runes) {
    buffer.write(_vietnameseFold[rune] ?? String.fromCharCode(rune));
  }
  return buffer.toString();
}

/// Lọc theo trạng thái và từ khóa (mã bàn, tên bàn, khu vực).
List<FloorPlanTable> filterTables(
  List<FloorPlanTable> tables, {
  TableFilter filter = TableFilter.all,
  String query = '',
}) {
  final needle = foldVietnamese(query.trim());
  return tables.where((item) {
    if (!filter.matches(item)) return false;
    if (needle.isEmpty) return true;
    final haystack = foldVietnamese(
      '${item.table.code} ${item.table.displayName} ${item.table.area ?? ''}',
    );
    return haystack.contains(needle);
  }).toList();
}

/// Số bàn theo từng bộ lọc (hiển thị trên chip).
Map<TableFilter, int> countByFilter(List<FloorPlanTable> tables) {
  final counts = <TableFilter, int>{
    for (final filter in TableFilter.values) filter: 0,
  };
  for (final item in tables) {
    for (final filter in TableFilter.values) {
      if (filter.matches(item)) counts[filter] = (counts[filter] ?? 0) + 1;
    }
  }
  return counts;
}

/// Nhóm bàn theo khu vực (giữ thứ tự bàn đã sắp xếp từ backend).
class AreaGroup {
  const AreaGroup(this.area, this.tables);

  /// Null = bàn chưa gán khu vực.
  final String? area;
  final List<FloorPlanTable> tables;
}

List<AreaGroup> groupByArea(List<FloorPlanTable> tables) {
  final order = <String?>[];
  final buckets = <String?, List<FloorPlanTable>>{};
  for (final item in tables) {
    final raw = item.table.area?.trim();
    final key = (raw == null || raw.isEmpty) ? null : raw;
    if (!buckets.containsKey(key)) {
      buckets[key] = <FloorPlanTable>[];
      order.add(key);
    }
    buckets[key]!.add(item);
  }
  return <AreaGroup>[for (final key in order) AreaGroup(key, buckets[key]!)];
}

/// Sự kiện `/topic/tables` (backend `TableTopicMessage`).
class TableChangedEvent {
  const TableChangedEvent({
    required this.eventId,
    required this.tableId,
    required this.status,
    required this.active,
    required this.version,
    required this.change,
    this.sessionId,
  });

  /// Trả về null nếu không phải sự kiện `TABLE_CHANGED` hợp lệ.
  static TableChangedEvent? tryParse(RealtimeMessage message) {
    if (message.destination != StompTopics.tables) return null;
    final payload = message.payload;
    if (asString(payload['type']) != 'TABLE_CHANGED') return null;
    final tableId = asIntOrNull(payload['tableId']);
    final status = TableStatus.parse(payload['status']);
    if (tableId == null || status == null) return null;
    return TableChangedEvent(
      eventId: asInt(payload['eventId']),
      tableId: tableId,
      status: status,
      active: asBool(payload['active'], fallback: true),
      version: asInt(payload['version']),
      change: asString(payload['change']),
      sessionId: asIntOrNull(payload['sessionId']),
    );
  }

  final int eventId;
  final int tableId;
  final TableStatus status;
  final bool active;
  final int version;
  final String change;
  final int? sessionId;
}

/// Áp sự kiện lên danh sách bàn để giao diện đổi trạng thái tức thì; dữ liệu
/// chuẩn sẽ được tải lại bằng REST ngay sau đó. Bỏ qua sự kiện cũ (version nhỏ
/// hơn hoặc bằng bản đang có).
List<FloorPlanTable> applyTableEvent(
  List<FloorPlanTable> tables,
  TableChangedEvent event,
) {
  return tables.map((item) {
    if (item.table.id != event.tableId) return item;
    if (event.version <= item.table.version) return item;
    final updated = item.table.copyWith(
      status: event.status,
      active: event.active,
      version: event.version,
    );
    // Bàn trống/đang dọn không còn phiên hoạt động.
    final noSession =
        event.status == TableStatus.available ||
        event.status == TableStatus.cleaning;
    return item.copyWith(table: updated, clearSession: noSession);
  }).toList();
}
