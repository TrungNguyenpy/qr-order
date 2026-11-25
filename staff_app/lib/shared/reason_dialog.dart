import 'package:flutter/material.dart';

import '../app/theme.dart';
import '../features/orders/domain/order_action_models.dart';

/// Lý do được chọn: mã chuẩn UPPER_SNAKE_CASE và ghi chú tùy chọn.
class ChosenReason {
  const ChosenReason(this.code, this.note);

  final String code;
  final String? note;
}

/// Hộp thoại chọn lý do từ chối (món/đợt). Trả null nếu hủy.
Future<ChosenReason?> showRejectReasonDialog(
  BuildContext context, {
  required String title,
  String? subtitle,
  String confirmLabel = 'Từ chối',
}) {
  return showDialog<ChosenReason>(
    context: context,
    builder: (_) => _ReasonDialog(
      title: title,
      subtitle: subtitle,
      confirmLabel: confirmLabel,
    ),
  );
}

class _ReasonDialog extends StatefulWidget {
  const _ReasonDialog({
    required this.title,
    required this.subtitle,
    required this.confirmLabel,
  });

  final String title;
  final String? subtitle;
  final String confirmLabel;

  @override
  State<_ReasonDialog> createState() => _ReasonDialogState();
}

class _ReasonDialogState extends State<_ReasonDialog> {
  ReasonPreset _selected = ReasonPreset.rejection.first;
  final TextEditingController _note = TextEditingController();

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            if (widget.subtitle != null) ...<Widget>[
              Text(
                widget.subtitle!,
                style: const TextStyle(color: AppColors.muted),
              ),
              const SizedBox(height: AppSpacing.md),
            ],
            Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.sm,
              children: <Widget>[
                for (final preset in ReasonPreset.rejection)
                  ChoiceChip(
                    label: Text(preset.label),
                    selected: preset == _selected,
                    onSelected: (_) => setState(() => _selected = preset),
                  ),
              ],
            ),
            const SizedBox(height: AppSpacing.md),
            TextField(
              controller: _note,
              maxLength: 1000,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: 'Ghi chú (tùy chọn)',
              ),
            ),
          ],
        ),
      ),
      actions: <Widget>[
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Hủy'),
        ),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
          onPressed: () => Navigator.of(context).pop(
            ChosenReason(
              _selected.code,
              _note.text.trim().isEmpty ? null : _note.text.trim(),
            ),
          ),
          child: Text(widget.confirmLabel),
        ),
      ],
    );
  }
}

/// Hộp thoại nhập lý do tự do bắt buộc (ví dụ từ chối yêu cầu hủy món).
Future<String?> showRequiredTextDialog(
  BuildContext context, {
  required String title,
  required String label,
  String confirmLabel = 'Gửi',
}) {
  return showDialog<String>(
    context: context,
    builder: (_) => _RequiredTextDialog(
      title: title,
      label: label,
      confirmLabel: confirmLabel,
    ),
  );
}

class _RequiredTextDialog extends StatefulWidget {
  const _RequiredTextDialog({
    required this.title,
    required this.label,
    required this.confirmLabel,
  });

  final String title;
  final String label;
  final String confirmLabel;

  @override
  State<_RequiredTextDialog> createState() => _RequiredTextDialogState();
}

class _RequiredTextDialogState extends State<_RequiredTextDialog> {
  final TextEditingController _controller = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _submit() {
    final text = _controller.text.trim();
    if (text.isEmpty) {
      setState(() => _error = 'Vui lòng nhập ${widget.label.toLowerCase()}');
      return;
    }
    Navigator.of(context).pop(text);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: TextField(
        controller: _controller,
        autofocus: true,
        maxLength: 1000,
        maxLines: 3,
        decoration: InputDecoration(labelText: widget.label, errorText: _error),
      ),
      actions: <Widget>[
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Hủy'),
        ),
        FilledButton(onPressed: _submit, child: Text(widget.confirmLabel)),
      ],
    );
  }
}

/// Hộp thoại nhập ghi chú tùy chọn. Trả chuỗi (có thể rỗng) khi xác nhận,
/// `null` khi hủy.
Future<String?> showOptionalTextDialog(
  BuildContext context, {
  required String title,
  required String label,
  String confirmLabel = 'Gửi',
}) {
  return showDialog<String>(
    context: context,
    builder: (_) => _OptionalTextDialog(
      title: title,
      label: label,
      confirmLabel: confirmLabel,
    ),
  );
}

class _OptionalTextDialog extends StatefulWidget {
  const _OptionalTextDialog({
    required this.title,
    required this.label,
    required this.confirmLabel,
  });

  final String title;
  final String label;
  final String confirmLabel;

  @override
  State<_OptionalTextDialog> createState() => _OptionalTextDialogState();
}

class _OptionalTextDialogState extends State<_OptionalTextDialog> {
  final TextEditingController _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: TextField(
        controller: _controller,
        maxLength: 500,
        maxLines: 2,
        decoration: InputDecoration(labelText: widget.label),
      ),
      actions: <Widget>[
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Hủy'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(_controller.text.trim()),
          child: Text(widget.confirmLabel),
        ),
      ],
    );
  }
}
