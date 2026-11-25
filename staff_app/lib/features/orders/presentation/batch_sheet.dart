import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/providers/core_providers.dart';
import '../../../core/util/format.dart';
import '../../../shared/reason_dialog.dart';
import '../../../shared/ui_actions.dart';
import '../../../shared/widgets/app_card.dart';
import '../../../shared/widgets/state_views.dart';
import '../application/order_lists.dart';
import '../data/orders_repository.dart';
import '../domain/order_models.dart';

/// Xử lý một đợt chờ xác nhận: tự "nhận xử lý" (claim) khi mở, cho từ chối từng
/// món hoặc cả đợt, rồi xác nhận. Đóng sheet mà chưa quyết định thì nhả claim.
Future<void> showBatchSheet(BuildContext context, {required OrderBatch batch}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    constraints: const BoxConstraints(maxWidth: 640),
    builder: (_) => _BatchSheet(initial: batch),
  );
}

class _BatchSheet extends ConsumerStatefulWidget {
  const _BatchSheet({required this.initial});

  final OrderBatch initial;

  @override
  ConsumerState<_BatchSheet> createState() => _BatchSheetState();
}

class _BatchSheetState extends ConsumerState<_BatchSheet> {
  late final OrdersRepository _repo = ref.read(ordersRepositoryProvider);
  late OrderBatch _batch = widget.initial;

  /// itemId -> lý do từ chối đã chọn cho món đó.
  final Map<int, ChosenReason> _rejected = <int, ChosenReason>{};

  bool _claiming = true;
  bool _submitting = false;
  bool _decided = false;
  ApiException? _claimError;

  @override
  void initState() {
    super.initState();
    unawaited(_claim());
  }

  @override
  void dispose() {
    // Chưa quyết định mà đóng sheet: trả claim cho người khác (best-effort).
    if (!_decided && _claimError == null) {
      unawaited(_releaseQuietly(_repo, _batch.id));
    }
    super.dispose();
  }

  static Future<void> _releaseQuietly(OrdersRepository repo, int id) async {
    try {
      await repo.releaseBatch(id);
    } on ApiException {
      // Claim đã hết hạn hoặc không còn của mình: bỏ qua.
    }
  }

  Future<void> _claim() async {
    try {
      final claimed = await _repo.claimBatch(_batch.id);
      if (!mounted) return;
      setState(() {
        _batch = claimed;
        _claimError = null;
        _claiming = false;
      });
    } on ApiException catch (error) {
      if (!mounted) return;
      setState(() {
        _claimError = error;
        _claiming = false;
      });
    }
  }

  Future<void> _refreshList() async {
    try {
      await ref.read(pendingBatchesProvider.notifier).reload();
    } on ApiException {
      // Danh sách sẽ tự làm mới qua realtime.
    }
  }

  Future<void> _toggleReject(OrderItem item) async {
    if (_rejected.containsKey(item.id)) {
      setState(() => _rejected.remove(item.id));
      return;
    }
    final reason = await showRejectReasonDialog(
      context,
      title: 'Từ chối món',
      subtitle: '${item.quantity}× ${item.productName}',
    );
    if (reason == null || !mounted) return;
    setState(() => _rejected[item.id] = reason);
  }

  Future<void> _confirm() async {
    final rejections = _rejected.entries
        .map(
          (e) => ItemRejection(
            itemId: e.key,
            reasonCode: e.value.code,
            reasonNote: e.value.note,
          ),
        )
        .toList();
    setState(() => _submitting = true);
    final ok = await runGuarded(
      context,
      () async {
        await _repo.confirmBatch(_batch.id, rejectItems: rejections);
      },
      success: rejections.isEmpty
          ? 'Đã xác nhận đợt ${_batch.seq}.'
          : 'Đã xác nhận đợt ${_batch.seq}, từ chối ${rejections.length} món.',
      onError: (error) async {
        // Đợt đã được người khác xử lý / claim hết hạn: tải lại danh sách.
        await _refreshList();
      },
    );
    if (!mounted) return;
    setState(() => _submitting = false);
    if (ok) {
      _decided = true;
      await _refreshList();
      if (mounted) Navigator.of(context).pop();
    }
  }

  Future<void> _rejectAll() async {
    final reason = await showRejectReasonDialog(
      context,
      title: 'Từ chối cả đợt ${_batch.seq}',
      subtitle: 'Tất cả món trong đợt sẽ bị từ chối.',
      confirmLabel: 'Từ chối cả đợt',
    );
    if (reason == null || !mounted) return;
    setState(() => _submitting = true);
    final ok = await runGuarded(
      context,
      () async {
        await _repo.rejectBatch(
          _batch.id,
          reasonCode: reason.code,
          reasonNote: reason.note,
        );
      },
      success: 'Đã từ chối đợt ${_batch.seq}.',
      onError: (error) async => _refreshList(),
    );
    if (!mounted) return;
    setState(() => _submitting = false);
    if (ok) {
      _decided = true;
      await _refreshList();
      if (mounted) Navigator.of(context).pop();
    }
  }

  @override
  Widget build(BuildContext context) {
    final pendingItems = _batch.items;
    final allRejected =
        pendingItems.isNotEmpty && _rejected.length == pendingItems.length;
    final blocked = _claimError != null;
    final canAct = !_claiming && !blocked && !_submitting;
    final submitted = _batch.submittedAt;

    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.lg,
        0,
        AppSpacing.lg,
        AppSpacing.xl,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Row(
            children: <Widget>[
              Text(
                _batch.tableCode ?? 'Bàn',
                style: const TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Text(
                'Đợt ${_batch.seq}',
                style: const TextStyle(color: AppColors.muted, fontSize: 16),
              ),
              const Spacer(),
              if (submitted != null)
                Text(
                  'Gửi ${formatClock(submitted)}',
                  style: const TextStyle(color: AppColors.muted),
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.md),
          if (_claiming)
            const InlineBanner(
              message: 'Đang nhận xử lý đợt này...',
              foreground: AppColors.info,
              background: AppColors.infoSoft,
            )
          else if (blocked)
            InlineBanner(
              message: _claimError!.userMessage,
              icon: Icons.lock_outline_rounded,
              foreground: AppColors.warning,
              background: AppColors.warningSoft,
              trailing: TextButton(
                onPressed: () {
                  setState(() {
                    _claiming = true;
                    _claimError = null;
                  });
                  unawaited(_claim());
                },
                child: const Text('Thử lại'),
              ),
            )
          else
            const InlineBanner(
              message: 'Bạn đang xử lý đợt này. Xác nhận sớm để người khác không phải chờ.',
              icon: Icons.check_circle_outline_rounded,
              foreground: AppColors.success,
              background: AppColors.successSoft,
            ),
          const SizedBox(height: AppSpacing.md),
          AppCard(
            padding: EdgeInsets.zero,
            child: Column(
              children: <Widget>[
                for (var i = 0; i < pendingItems.length; i++) ...<Widget>[
                  if (i > 0) const Divider(height: 1),
                  _BatchItemRow(
                    item: pendingItems[i],
                    rejection: _rejected[pendingItems[i].id],
                    enabled: canAct,
                    onToggle: () => _toggleReject(pendingItems[i]),
                  ),
                ],
              ],
            ),
          ),
          if (allRejected) ...<Widget>[
            const SizedBox(height: AppSpacing.sm),
            const Text(
              'Đã chọn từ chối mọi món: hãy dùng "Từ chối cả đợt".',
              style: TextStyle(color: AppColors.warning, fontSize: 12.5),
            ),
          ],
          const SizedBox(height: AppSpacing.lg),
          FilledButton(
            onPressed: canAct && !allRejected ? _confirm : null,
            child: _submitting
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : Text(
                    _rejected.isEmpty
                        ? 'Xác nhận đợt'
                        : 'Xác nhận ${pendingItems.length - _rejected.length} món, từ chối ${_rejected.length}',
                  ),
          ),
          const SizedBox(height: AppSpacing.sm),
          OutlinedButton(
            style: OutlinedButton.styleFrom(foregroundColor: AppColors.danger),
            onPressed: canAct ? _rejectAll : null,
            child: const Text('Từ chối cả đợt'),
          ),
        ],
      ),
    );
  }
}

class _BatchItemRow extends StatelessWidget {
  const _BatchItemRow({
    required this.item,
    required this.rejection,
    required this.enabled,
    required this.onToggle,
  });

  final OrderItem item;
  final ChosenReason? rejection;
  final bool enabled;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final rejected = rejection != null;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          SizedBox(
            width: 32,
            child: Text(
              '${item.quantity}×',
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  item.productName,
                  style: TextStyle(
                    fontWeight: FontWeight.w600,
                    decoration: rejected ? TextDecoration.lineThrough : null,
                    color: rejected ? AppColors.muted : AppColors.text,
                  ),
                ),
                if (item.options.isNotEmpty)
                  Text(
                    item.options.map((o) => o.optionName).join(', '),
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 12.5,
                    ),
                  ),
                if ((item.note ?? '').isNotEmpty)
                  Text(
                    'Ghi chú: ${item.note}',
                    style: const TextStyle(
                      color: AppColors.warning,
                      fontSize: 12.5,
                    ),
                  ),
                if (rejected)
                  Text(
                    'Từ chối: ${rejection!.code}',
                    style: const TextStyle(
                      color: AppColors.danger,
                      fontSize: 12.5,
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(width: AppSpacing.sm),
          Text(formatVnd(item.lineTotal)),
          IconButton(
            tooltip: rejected ? 'Bỏ từ chối' : 'Từ chối món này',
            onPressed: enabled ? onToggle : null,
            icon: Icon(
              rejected ? Icons.undo_rounded : Icons.block_rounded,
              size: 20,
              color: rejected ? AppColors.muted : AppColors.danger,
            ),
          ),
        ],
      ),
    );
  }
}
