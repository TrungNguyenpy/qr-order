import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/network/api_exception.dart';
import '../../../shared/widgets/app_card.dart';
import '../../../shared/widgets/state_views.dart';
import '../../tables/application/floor_plan_controller.dart';
import '../../tables/domain/table_models.dart';
import '../../tables/presentation/table_style.dart';
import '../application/billing_providers.dart';
import 'attention_sheet.dart';
import 'cashier_sheet.dart';

/// Bàn cần thu ngân quan tâm: đang phục vụ hoặc chờ thanh toán, chờ thanh toán trước.
List<FloorPlanTable> cashierTables(List<FloorPlanTable> all) {
  final relevant = all
      .where(
        (t) =>
            t.activeSession != null &&
            (t.table.status == TableStatus.occupied ||
                t.table.status == TableStatus.paymentPending),
      )
      .toList();
  int rank(FloorPlanTable t) =>
      t.table.status == TableStatus.paymentPending ? 0 : 1;
  relevant.sort((a, b) {
    final byRank = rank(a).compareTo(rank(b));
    return byRank != 0 ? byRank : a.table.code.compareTo(b.table.code);
  });
  return relevant;
}

/// Tab Thu ngân: chọn bàn để tính tiền và thu tiền.
class CashierScreen extends ConsumerWidget {
  const CashierScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final plan = ref.watch(floorPlanProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Thu ngân'),
        actions: <Widget>[
          _AttentionButton(onPressed: () => showAttentionSheet(context)),
        ],
      ),
      body: plan.when(
        loading: () => const _CashierSkeleton(),
        error: (error, _) => ErrorView(
          error: error,
          onRetry: () => ref.invalidate(floorPlanProvider),
        ),
        data: (tables) {
          final list = cashierTables(tables);
          return RefreshIndicator(
            onRefresh: () async {
              final messenger = ScaffoldMessenger.of(context);
              try {
                await ref.read(floorPlanProvider.notifier).reload();
              } on ApiException catch (error) {
                messenger.showSnackBar(
                  SnackBar(content: Text(error.userMessage)),
                );
              }
            },
            child: list.isEmpty
                ? ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    children: const <Widget>[
                      SizedBox(height: 80),
                      EmptyView(
                        icon: Icons.payments_outlined,
                        title: 'Chưa có bàn cần thanh toán',
                        message: 'Bàn đang phục vụ hoặc chờ thanh toán sẽ hiện ở đây.',
                      ),
                    ],
                  )
                : GridView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.all(AppSpacing.lg),
                    gridDelegate:
                        const SliverGridDelegateWithMaxCrossAxisExtent(
                          maxCrossAxisExtent: 220,
                          mainAxisExtent: 92,
                          mainAxisSpacing: AppSpacing.sm,
                          crossAxisSpacing: AppSpacing.sm,
                        ),
                    itemCount: list.length,
                    itemBuilder: (context, index) =>
                        _CashierTableCard(item: list[index]),
                  ),
          );
        },
      ),
    );
  }
}

class _CashierTableCard extends StatelessWidget {
  const _CashierTableCard({required this.item});

  final FloorPlanTable item;

  @override
  Widget build(BuildContext context) {
    final table = item.table;
    final style = styleForTableStatus(table.status);
    final session = item.activeSession!;
    return AppCard(
      onTap: () => showCashierSheet(
        context,
        sessionId: session.id,
        tableCode: table.code,
      ),
      borderColor: table.status == TableStatus.paymentPending
          ? AppColors.warning
          : AppColors.border,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(
            table.code,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          Text(
            table.displayName,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: AppColors.muted, fontSize: 12.5),
          ),
          const Spacer(),
          StatusPill(
            label: table.status.label,
            foreground: style.foreground,
            background: style.background,
          ),
        ],
      ),
    );
  }
}

class _CashierSkeleton extends StatelessWidget {
  const _CashierSkeleton();

  @override
  Widget build(BuildContext context) {
    return GridView.builder(
      physics: const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.all(AppSpacing.lg),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 220,
        mainAxisExtent: 92,
        mainAxisSpacing: AppSpacing.sm,
        crossAxisSpacing: AppSpacing.sm,
      ),
      itemCount: 6,
      itemBuilder: (_, __) => const AppCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            SkeletonBox(width: 50, height: 16),
            SizedBox(height: 6),
            SkeletonBox(width: 90, height: 11),
          ],
        ),
      ),
    );
  }
}

/// Nút "Cần xử lý" kèm số mục đang chờ (hoàn tiền, hóa đơn thay thế).
class _AttentionButton extends ConsumerWidget {
  const _AttentionButton({required this.onPressed});

  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final count = ref.watch(attentionProvider).valueOrNull?.length ?? 0;
    return TextButton.icon(
      onPressed: onPressed,
      icon: const Icon(Icons.priority_high_rounded, size: 18),
      label: Text(count == 0 ? 'Cần xử lý' : 'Cần xử lý ($count)'),
    );
  }
}
