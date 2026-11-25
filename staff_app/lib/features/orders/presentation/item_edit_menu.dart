import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/providers/core_providers.dart';
import '../../../shared/reason_dialog.dart';
import '../../../shared/ui_actions.dart';
import '../application/session_order_provider.dart';
import '../domain/order_models.dart';

/// Menu "Sửa số lượng / Hủy món" của nhân viên cho món PENDING/CONFIRMED.
/// Món đã vào chế biến phải đi qua yêu cầu hủy (Manager duyệt), không có ở đây.
class ItemEditMenu extends ConsumerWidget {
  const ItemEditMenu({super.key, required this.item, required this.sessionId});

  final OrderItem item;
  final int sessionId;

  Future<void> _refresh(WidgetRef ref) async {
    ref.invalidate(sessionOrderProvider(sessionId));
  }

  Future<void> _changeQuantity(BuildContext context, WidgetRef ref) async {
    final quantity = await showQuantityDialog(
      context,
      title: 'Số lượng ${item.productName}',
      initial: item.quantity,
    );
    if (quantity == null || quantity == item.quantity || !context.mounted) {
      return;
    }
    await runGuarded(
      context,
      () => ref
          .read(ordersRepositoryProvider)
          .changeQuantity(item.id, quantity: quantity, version: item.version),
      success: 'Đã đổi số lượng.',
      // Xung đột phiên bản hoặc món đã đổi trạng thái: dữ liệu cũ, tải lại ngay.
      onError: (ApiException _) => _refresh(ref),
    );
    await _refresh(ref);
  }

  Future<void> _cancel(BuildContext context, WidgetRef ref) async {
    final reason = await showRequiredTextDialog(
      context,
      title: 'Hủy ${item.quantity}× ${item.productName}',
      label: 'Lý do hủy món',
      confirmLabel: 'Hủy món',
    );
    if (reason == null || !context.mounted) return;
    await runGuarded(
      context,
      () => ref
          .read(ordersRepositoryProvider)
          .cancelItem(item.id, reason: reason),
      success: 'Đã hủy món.',
      onError: (ApiException _) => _refresh(ref),
    );
    await _refresh(ref);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return PopupMenuButton<String>(
      tooltip: 'Sửa món',
      icon: const Icon(Icons.more_vert_rounded, size: 20),
      padding: EdgeInsets.zero,
      onSelected: (value) {
        if (value == 'quantity') _changeQuantity(context, ref);
        if (value == 'cancel') _cancel(context, ref);
      },
      itemBuilder: (_) => const <PopupMenuEntry<String>>[
        PopupMenuItem<String>(value: 'quantity', child: Text('Sửa số lượng')),
        PopupMenuItem<String>(value: 'cancel', child: Text('Hủy món')),
      ],
    );
  }
}

/// Hộp thoại chọn số lượng bằng nút +/- (≥ 1). Trả null khi hủy.
Future<int?> showQuantityDialog(
  BuildContext context, {
  required String title,
  required int initial,
}) {
  return showDialog<int>(
    context: context,
    builder: (_) => _QuantityDialog(title: title, initial: initial),
  );
}

class _QuantityDialog extends StatefulWidget {
  const _QuantityDialog({required this.title, required this.initial});

  final String title;
  final int initial;

  @override
  State<_QuantityDialog> createState() => _QuantityDialogState();
}

class _QuantityDialogState extends State<_QuantityDialog> {
  late int _value = widget.initial < 1 ? 1 : widget.initial;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: <Widget>[
          IconButton.outlined(
            onPressed: _value > 1 ? () => setState(() => _value--) : null,
            icon: const Icon(Icons.remove_rounded),
            tooltip: 'Giảm',
          ),
          SizedBox(
            width: 64,
            child: Text(
              '$_value',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700),
            ),
          ),
          IconButton.outlined(
            onPressed: () => setState(() => _value++),
            icon: const Icon(Icons.add_rounded),
            tooltip: 'Tăng',
          ),
        ],
      ),
      actions: <Widget>[
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Hủy'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(_value),
          child: const Text('Lưu'),
        ),
      ],
    );
  }
}
