// Tiện ích đọc JSON khoan dung: backend trả đúng kiểu, nhưng client không được
// crash khi một trường tùy chọn thiếu hoặc đổi kiểu (ví dụ Long -> số hoặc chuỗi).

int? asIntOrNull(Object? value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  if (value is String) return int.tryParse(value);
  return null;
}

int asInt(Object? value, {int fallback = 0}) => asIntOrNull(value) ?? fallback;

String? asStringOrNull(Object? value) {
  if (value == null) return null;
  if (value is String) return value;
  return value.toString();
}

String asString(Object? value, {String fallback = ''}) =>
    asStringOrNull(value) ?? fallback;

bool asBool(Object? value, {bool fallback = false}) {
  if (value is bool) return value;
  if (value is String) return value.toLowerCase() == 'true';
  return fallback;
}

/// Đọc thời điểm ISO-8601 (`Instant` hoặc `OffsetDateTime` của backend) hoặc
/// số giây epoch. Trả về null nếu không đọc được.
DateTime? asDateTime(Object? value) {
  if (value is String) return DateTime.tryParse(value);
  if (value is num) {
    return DateTime.fromMillisecondsSinceEpoch(
      (value * 1000).round(),
      isUtc: true,
    );
  }
  return null;
}

Map<String, dynamic> asMap(Object? value) {
  if (value is Map) return Map<String, dynamic>.from(value);
  return <String, dynamic>{};
}

List<Map<String, dynamic>> asMapList(Object? value) {
  if (value is List) {
    return value
        .whereType<Map<dynamic, dynamic>>()
        .map((item) => Map<String, dynamic>.from(item))
        .toList();
  }
  return <Map<String, dynamic>>[];
}
