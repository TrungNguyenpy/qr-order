import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/util/format.dart';
import '../../../shared/widgets/app_card.dart';
import '../../../shared/widgets/state_views.dart';
import '../application/billing_providers.dart';
import '../domain/billing_models.dart';
import 'cashier_sheet.dart';

/// Danh sách "Cần xử lý": hóa đơn chờ hoàn tiền và order chờ hóa đơn thay thế.
/// Mọi nhân viên sàn xem được; thao tác tùy quyền trong sheet của từng bàn.
Future<void> showAttentionSheet(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    constraints: const BoxConstraints(maxWidth: 680),
    builder: (_) => const _AttentionSheet(),
  );
}

class _AttentionSheet extends ConsumerWidget {
  const _AttentionSheet();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final items = ref.watch(attentionProvider);
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.lg,
        0,
        AppSpacing.lg,
        AppSpacing.xl,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          const Text(
            'Cần xử lý',
            style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: AppSpacing.md),
          items.when(
            loading: () => const AppCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  SkeletonBox(width: 120, height: 16),
                  SizedBox(height: 8),
                  SkeletonBox(height: 12),
                ],
              ),
            ),
            error: (error, _) => ErrorView(
              error: error,
              onRetry: () => ref.invalidate(attentionProvider),
            ),
            data: (list) => list.isEmpty
                ? const EmptyView(
                    icon: Icons.task_alt_rounded,
                    title: 'Không có gì cần xử lý',
                    message: 'Hóa đơn chờ hoàn tiền, chờ hóa đơn thay thế hoặc chờ thu tiền sẽ hiện ở đây.',
                  )
                : Flexible(
                    child: ListView(
                      shrinkWrap: true,
                      children: <Widget>[
                        for (final entry in groupAttention(
                          list,
                        ).entries) ...<Widget>[
                          Padding(
                            padding: const EdgeInsets.only(
                              top: AppSpacing.sm,
                              bottom: AppSpacing.sm,
                            ),
                            child: Text(
                              '${_groupTitle(entry.key)} (${entry.value.length})',
                              style: const TextStyle(
                                color: AppColors.muted,
                                fontSize: 12.5,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                          for (final item in entry.value)
                            Padding(
                              padding: const EdgeInsets.only(
                                bottom: AppSpacing.sm,
                              ),
                              child: _AttentionCard(item: item),
                            ),
                        ],
                      ],
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}

String _groupTitle(AttentionType type) => switch (type) {
  AttentionType.billVoidPending => 'Hóa đơn chờ hoàn tiền',
  AttentionType.awaitingReplacement => 'Chờ tạo hóa đơn thay thế',
  AttentionType.awaitingPayment =>
    'Hóa đơn chờ thu tiền (phiên đã đóng / hóa đơn thay thế)',
};

class _AttentionCard extends StatelessWidget {
  const _AttentionCard({required this.item});

  final AttentionItem item;

  @override
  Widget build(BuildContext context) {
    final table = item.tableCode ?? '—';
    return AppCard(
      onTap: () {
        Navigator.of(context).pop();
        showCashierSheet(context, sessionId: item.sessionId, tableCode: table);
      },
      child: Row(
        children: <Widget>[
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  'Bàn $table · ${item.type.label}',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                if (item.billNumber != null) Text(item.billNumber!),
                if (item.remainingAmount != null)
                  Text(
                    'Còn phải thu ${formatVnd(item.remainingAmount!)}',
                    style: const TextStyle(color: AppColors.danger),
                  ),
                if (item.remainingRefundable != null)
                  Text(
                    'Còn phải hoàn ${formatVnd(item.remainingRefundable!)}',
                    style: const TextStyle(color: AppColors.danger),
                  ),
                if ((item.reason ?? '').isNotEmpty)
                  Text(
                    item.reason!,
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 12.5,
                    ),
                  ),
                if (item.since != null)
                  Text(
                    'Từ ${formatDayClock(item.since!)}',
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 12,
                    ),
                  ),
              ],
            ),
          ),
          const Icon(Icons.chevron_right_rounded, color: AppColors.muted),
        ],
      ),
    );
  }
}
