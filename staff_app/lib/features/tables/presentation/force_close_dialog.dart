import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/providers/core_providers.dart';
import '../../../shared/widgets/state_views.dart';
import '../domain/force_close_policy.dart';
import '../domain/table_models.dart';

/// Kết quả hộp thoại đóng cưỡng bức phiên.
class ForceCloseResult {
  const ForceCloseResult({this.closed, this.openCashier = false});

  /// Phiên đã đóng thành công (null nếu người dùng hủy hoặc chưa đóng được).
  final StaffSession? closed;

  /// Người dùng chọn sang màn Thu ngân để xử lý Bill chặn việc đóng phiên.
  final bool openCashier;
}

/// Hộp thoại đóng cưỡng bức phiên (BR-S06, FR-SES-03): lý do bắt buộc, cảnh báo hậu
/// quả, hướng dẫn rõ khi Bill đang chờ hoàn tiền (`BILL_VOID_PENDING`) hoặc còn
/// giao dịch thanh toán chờ (`PENDING_PAYMENT_EXISTS`). Luôn tải lại dữ liệu ở nơi gọi
/// sau khi hộp thoại đóng.
Future<ForceCloseResult?> showForceCloseDialog(
  BuildContext context, {
  required int sessionId,
  required String tableCode,
  int? version,
}) {
  return showDialog<ForceCloseResult>(
    context: context,
    barrierDismissible: false,
    builder: (_) => ForceCloseDialog(
      sessionId: sessionId,
      tableCode: tableCode,
      version: version,
    ),
  );
}

class ForceCloseDialog extends ConsumerStatefulWidget {
  const ForceCloseDialog({
    super.key,
    required this.sessionId,
    required this.tableCode,
    this.version,
  });

  final int sessionId;
  final String tableCode;
  final int? version;

  @override
  ConsumerState<ForceCloseDialog> createState() => _ForceCloseDialogState();
}

class _ForceCloseDialogState extends ConsumerState<ForceCloseDialog> {
  final TextEditingController _reason = TextEditingController();
  bool _busy = false;
  String? _reasonError;
  ForceCloseFailure? _failure;
  String? _serverMessage;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final error = validateForceCloseReason(_reason.text);
    if (error != null) {
      setState(() => _reasonError = error);
      return;
    }
    setState(() {
      _busy = true;
      _reasonError = null;
      _failure = null;
      _serverMessage = null;
    });
    try {
      final session = await ref
          .read(tablesRepositoryProvider)
          .forceClose(
            widget.sessionId,
            reason: _reason.text.trim(),
            version: widget.version,
          );
      if (!mounted) return;
      Navigator.of(context).pop(ForceCloseResult(closed: session));
    } on ApiException catch (error) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _failure = classifyForceCloseError(error);
        _serverMessage = error.userMessage;
      });
    } on Object {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _failure = ForceCloseFailure.other;
        _serverMessage = 'Đã xảy ra lỗi không mong muốn. Vui lòng thử lại.';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final failure = _failure;
    final needsOther = failure != null && forceCloseNeedsOtherAction(failure);
    // Phiên đã đổi/kết thúc hoặc không đủ quyền: không thể gửi lại, chỉ cho đóng hộp thoại.
    final terminal =
        failure == ForceCloseFailure.staleOrEnded ||
        failure == ForceCloseFailure.forbidden;
    return AlertDialog(
      title: Text('Đóng cưỡng bức phiên · ${widget.tableCode}'),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              const InlineBanner(
                message:
                    'Không thể hoàn tác. Món đang chờ xác nhận sẽ bị từ chối. Nếu còn '
                    'món tính tiền chưa thu đủ, hệ thống ghi nhận thất thu (không tính '
                    'vào doanh thu). Bàn chuyển sang Đang dọn.',
                icon: Icons.warning_amber_rounded,
                foreground: AppColors.danger,
                background: AppColors.dangerSoft,
              ),
              const SizedBox(height: AppSpacing.md),
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                children: <Widget>[
                  for (final preset in forceClosePresetReasons)
                    ActionChip(
                      label: Text(preset),
                      onPressed: _busy
                          ? null
                          : () => setState(() {
                              _reason.text = preset;
                              _reason.selection = TextSelection.collapsed(
                                offset: preset.length,
                              );
                              _reasonError = null;
                            }),
                    ),
                ],
              ),
              const SizedBox(height: AppSpacing.md),
              TextField(
                controller: _reason,
                enabled: !_busy && !terminal,
                maxLength: 1000,
                maxLines: 3,
                onChanged: (_) {
                  if (_reasonError != null) setState(() => _reasonError = null);
                },
                decoration: InputDecoration(
                  labelText: 'Lý do đóng cưỡng bức (bắt buộc)',
                  errorText: _reasonError,
                ),
              ),
              if (failure != null) ...<Widget>[
                const SizedBox(height: AppSpacing.sm),
                InlineBanner(
                  message: forceCloseGuidance(failure) ?? _serverMessage ?? '',
                  icon: Icons.error_outline_rounded,
                  foreground: AppColors.danger,
                  background: AppColors.dangerSoft,
                ),
              ],
            ],
          ),
        ),
      ),
      actions: <Widget>[
        TextButton(
          onPressed: _busy
              ? null
              : () => Navigator.of(context).pop(const ForceCloseResult()),
          child: Text(terminal ? 'Đóng' : 'Hủy'),
        ),
        if (needsOther)
          OutlinedButton(
            onPressed: _busy
                ? null
                : () => Navigator.of(
                    context,
                  ).pop(const ForceCloseResult(openCashier: true)),
            child: const Text('Mở màn Thu ngân'),
          ),
        if (!terminal)
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
            onPressed: _busy ? null : _submit,
            child: _busy
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : Text(needsOther ? 'Thử lại' : 'Đóng cưỡng bức'),
          ),
      ],
    );
  }
}
