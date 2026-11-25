/// Định dạng hiển thị dùng chung (không phụ thuộc gói `intl`).
library;

/// 1234567 -> `1.234.567 đ`.
String formatVnd(int amount) {
  final negative = amount < 0;
  final digits = amount.abs().toString();
  final buffer = StringBuffer();
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 == 0) buffer.write('.');
    buffer.write(digits[i]);
  }
  return '${negative ? '-' : ''}$buffer đ';
}

String _two(int value) => value.toString().padLeft(2, '0');

/// Giờ phút theo múi giờ của thiết bị: `14:05`.
String formatClock(DateTime time) {
  final local = time.toLocal();
  return '${_two(local.hour)}:${_two(local.minute)}';
}

/// Ngày giờ ngắn: `24/11 14:05`.
String formatDayClock(DateTime time) {
  final local = time.toLocal();
  return '${_two(local.day)}/${_two(local.month)} ${formatClock(local)}';
}

/// Khoảng thời gian đã trôi qua từ [from] đến [now]: `vừa xong`, `12 phút`,
/// `1 giờ 05`, `2 ngày`.
String formatElapsed(DateTime from, DateTime now) {
  final diff = now.difference(from);
  if (diff.isNegative || diff.inSeconds < 60) return 'vừa xong';
  if (diff.inMinutes < 60) return '${diff.inMinutes} phút';
  if (diff.inHours < 24) {
    return '${diff.inHours} giờ ${_two(diff.inMinutes % 60)}';
  }
  return '${diff.inDays} ngày';
}
