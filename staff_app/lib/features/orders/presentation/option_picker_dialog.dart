import 'package:flutter/material.dart';

import '../../../app/theme.dart';
import '../../../core/util/format.dart';
import '../../menu/domain/menu_models.dart';

/// Chọn option, số lượng và ghi chú cho một món. Trả [CartLine] khi xác nhận,
/// null khi hủy. Chặn xác nhận nếu chưa chọn đủ nhóm bắt buộc / vượt tối đa.
Future<CartLine?> showOptionPickerDialog(
  BuildContext context, {
  required MenuProduct product,
  required List<OptionGroupDetail> groups,
}) {
  return showDialog<CartLine>(
    context: context,
    builder: (_) => _OptionPickerDialog(product: product, groups: groups),
  );
}

class _OptionPickerDialog extends StatefulWidget {
  const _OptionPickerDialog({required this.product, required this.groups});

  final MenuProduct product;
  final List<OptionGroupDetail> groups;

  @override
  State<_OptionPickerDialog> createState() => _OptionPickerDialogState();
}

class _OptionPickerDialogState extends State<_OptionPickerDialog> {
  final Map<int, Set<int>> _selected = <int, Set<int>>{};
  final TextEditingController _note = TextEditingController();
  int _quantity = 1;
  bool _showErrors = false;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  Set<int> _of(OptionGroupDetail group) =>
      _selected.putIfAbsent(group.id, () => <int>{});

  String? _error(OptionGroupDetail group) =>
      group.validateCount(_of(group).length);

  List<OptionChoice> get _choices => <OptionChoice>[
    for (final group in widget.groups)
      for (final item in group.items)
        if (_of(group).contains(item.id)) item,
  ];

  void _toggle(OptionGroupDetail group, OptionChoice item) {
    setState(() {
      final selected = _of(group);
      if (!group.multiple) {
        selected
          ..clear()
          ..add(item.id);
      } else if (!selected.remove(item.id)) {
        selected.add(item.id);
      }
    });
  }

  void _submit() {
    final invalid = widget.groups.any((g) => _error(g) != null);
    if (invalid) {
      setState(() => _showErrors = true);
      return;
    }
    Navigator.of(context).pop(
      CartLine(
        product: widget.product,
        quantity: _quantity,
        note: _note.text.trim(),
        choices: _choices,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final extra = _choices.fold<int>(0, (s, c) => s + c.extraPrice);
    return AlertDialog(
      title: Text(widget.product.name),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              for (final group in widget.groups) ...<Widget>[
                Text(
                  group.required ? '${group.name} (bắt buộc)' : group.name,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                if (_showErrors && _error(group) != null)
                  Text(
                    _error(group)!,
                    style: const TextStyle(
                      color: AppColors.danger,
                      fontSize: 12.5,
                    ),
                  ),
                Wrap(
                  spacing: AppSpacing.sm,
                  children: <Widget>[
                    for (final item in group.items)
                      FilterChip(
                        label: Text(
                          item.extraPrice > 0
                              ? '${item.name} +${formatVnd(item.extraPrice)}'
                              : item.name,
                        ),
                        selected: _of(group).contains(item.id),
                        onSelected: (_) => _toggle(group, item),
                      ),
                  ],
                ),
                const SizedBox(height: AppSpacing.md),
              ],
              TextField(
                controller: _note,
                maxLength: 500,
                decoration: const InputDecoration(
                  labelText: 'Ghi chú (tùy chọn)',
                ),
              ),
              Row(
                children: <Widget>[
                  const Text('Số lượng'),
                  const Spacer(),
                  IconButton(
                    onPressed: _quantity > 1
                        ? () => setState(() => _quantity--)
                        : null,
                    icon: const Icon(Icons.remove_circle_outline_rounded),
                    tooltip: 'Giảm',
                  ),
                  Text(
                    '$_quantity',
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                  IconButton(
                    onPressed: () => setState(() => _quantity++),
                    icon: const Icon(Icons.add_circle_outline_rounded),
                    tooltip: 'Tăng',
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
      actions: <Widget>[
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Hủy'),
        ),
        FilledButton(
          onPressed: _submit,
          child: Text(
            'Thêm · ${formatVnd((widget.product.price + extra) * _quantity)}',
          ),
        ),
      ],
    );
  }
}
