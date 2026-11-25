import 'package:flutter/material.dart';

import '../app/theme.dart';
import '../core/network/api_exception.dart';
import 'widgets/state_views.dart';

/// Chạy một thao tác gọi API: thành công trả `true` (và hiện [success] nếu có);
/// thất bại hiện thông điệp lỗi từ máy chủ rồi gọi [onError] (ví dụ tải lại dữ
/// liệu khi gặp xung đột phiên bản). Không bao giờ ném lỗi ra ngoài.
Future<bool> runGuarded(
  BuildContext context,
  Future<void> Function() action, {
  String? success,
  Future<void> Function(ApiException error)? onError,
}) async {
  final messenger = ScaffoldMessenger.of(context);
  try {
    await action();
    if (success != null) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(success)));
    }
    return true;
  } on ApiException catch (error) {
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(error.userMessage)));
    if (onError != null) await onError(error);
    return false;
  } on Object catch (error) {
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(errorMessage(error))));
    return false;
  }
}

/// Như [runGuarded] nhưng trả giá trị của thao tác (null khi thất bại).
Future<T?> runGuardedValue<T>(
  BuildContext context,
  Future<T> Function() action,
) async {
  T? value;
  final ok = await runGuarded(context, () async {
    value = await action();
  });
  return ok ? value : null;
}

/// Hộp thoại xác nhận có/không. Trả `true` khi người dùng đồng ý.
Future<bool> confirmDialog(
  BuildContext context, {
  required String title,
  required String message,
  String confirmLabel = 'Đồng ý',
  bool destructive = false,
}) async {
  final result = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text(title),
      content: Text(message),
      actions: <Widget>[
        TextButton(
          onPressed: () => Navigator.of(dialogContext).pop(false),
          child: const Text('Hủy'),
        ),
        FilledButton(
          style: destructive
              ? FilledButton.styleFrom(backgroundColor: AppColors.danger)
              : null,
          onPressed: () => Navigator.of(dialogContext).pop(true),
          child: Text(confirmLabel),
        ),
      ],
    ),
  );
  return result ?? false;
}
