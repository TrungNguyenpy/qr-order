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
import '../../../shared/widgets/live_list_view.dart';
import '../../auth/application/session_controller.dart';
import '../../auth/domain/auth_models.dart';
import '../application/order_lists.dart';
import '../../support/domain/service_call_models.dart';
import '../domain/order_action_models.dart';
import '../domain/order_models.dart';
import '../../support/application/service_call_providers.dart';
import '../../support/presentation/service_calls_tab.dart';
import 'batch_sheet.dart';

/// Tab Đơn hàng: đợt chờ xác nhận, món sẵn sàng mang ra, yêu cầu hủy món chờ duyệt.
class OrdersScreen extends ConsumerWidget {
  const OrdersScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(sessionControllerProvider).user;
    if (user == null) return const SizedBox.shrink();
    final canApprove = user.has(Permission.itemCancelApprove);

    final pending = ref.watch(pendingBatchesProvider).valueOrNull?.length;
    final ready = ref.watch(readyItemsProvider).valueOrNull?.length;
    final cancels = canApprove
        ? ref.watch(cancelRequestsProvider).valueOrNull?.length
        : null;

    final calls = ref.watch(serviceCallsProvider).valueOrNull;
    final openCalls = calls
        ?.where((c) => c.status == ServiceCallStatus.open)
        .length;

    String withCount(String label, int? count) =>
        count == null || count == 0 ? label : '$label ($count)';

    final tabs = <Tab>[
      Tab(text: withCount('Chờ xác nhận', pending)),
      Tab(text: withCount('Sẵn sàng', ready)),
      if (canApprove) Tab(text: withCount('Yêu cầu hủy', cancels)),
      Tab(text: withCount('Hỗ trợ', openCalls)),
    ];

    return DefaultTabController(
      length: tabs.length,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Đơn hàng'),
          bottom: TabBar(
            tabs: tabs,
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            labelColor: AppColors.primary,
            indicatorColor: AppColors.primary,
          ),
        ),
        body: TabBarView(
          children: <Widget>[
            const _PendingBatchesTab(),
            const _ReadyItemsTab(),
            if (canApprove) const _CancelRequestsTab(),
            const ServiceCallsTab(),
          ],
        ),
      ),
    );
  }
}

// ------------------------------------------------------------------ Chờ xác nhận

class _PendingBatchesTab extends ConsumerWidget {
  const _PendingBatchesTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final userId = ref.watch(sessionControllerProvider).user?.id ?? -1;
    return LiveListView<OrderBatch>(
      value: ref.watch(pendingBatchesProvider),
      onRefresh: () => _safeReload(
        context,
        ref.read(pendingBatchesProvider.notifier).reload,
      ),
      onRetry: () => ref.invalidate(pendingBatchesProvider),
      emptyIcon: Icons.task_alt_rounded,
      emptyTitle: 'Không có đợt nào chờ xác nhận',
      emptyMessage:
          'Đợt gọi món mới của khách sẽ hiện ở đây ngay khi được gửi.',
      itemBuilder: (context, batch) =>
          _PendingBatchCard(batch: batch, userId: userId),
    );
  }
}

Future<void> _safeReload(
  BuildContext context,
  Future<void> Function() reload,
) async {
  final messenger = ScaffoldMessenger.of(context);
  try {
    await reload();
  } on ApiException catch (error) {
    messenger.showSnackBar(SnackBar(content: Text(error.userMessage)));
  }
}

class _PendingBatchCard extends StatelessWidget {
  const _PendingBatchCard({required this.batch, required this.userId});

  final OrderBatch batch;
  final int userId;

  @override
  Widget build(BuildContext context) {
    final claimedByOther = batch.isClaimedByOther(userId);
    final mine = batch.isClaimedBy(userId);
    final submitted = batch.submittedAt;
    return AppCard(
      onTap: () => showBatchSheet(context, batch: batch),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              Text(
                batch.tableCode ?? 'Bàn',
                style: const TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Text(
                'Đợt ${batch.seq}',
                style: const TextStyle(color: AppColors.muted),
              ),
              const Spacer(),
              if (submitted != null)
                Text(
                  formatElapsed(submitted, DateTime.now()),
                  style: const TextStyle(
                    color: AppColors.warning,
                    fontSize: 12.5,
                  ),
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          for (final item in batch.items.take(4))
            Text(
              '${item.quantity}× ${item.productName}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          if (batch.items.length > 4)
            Text(
              '+ ${batch.items.length - 4} món khác',
              style: const TextStyle(color: AppColors.muted, fontSize: 12.5),
            ),
          if (claimedByOther || mine) ...<Widget>[
            const SizedBox(height: AppSpacing.sm),
            StatusPill(
              label: mine ? 'Bạn đang xử lý' : 'Đang có người xử lý',
              foreground: mine ? AppColors.success : AppColors.warning,
              background: mine ? AppColors.successSoft : AppColors.warningSoft,
            ),
          ],
        ],
      ),
    );
  }
}

// ------------------------------------------------------------------ Sẵn sàng

class _ReadyItemsTab extends ConsumerWidget {
  const _ReadyItemsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return LiveListView<KitchenItem>(
      value: ref.watch(readyItemsProvider),
      onRefresh: () =>
          _safeReload(context, ref.read(readyItemsProvider.notifier).reload),
      onRetry: () => ref.invalidate(readyItemsProvider),
      emptyIcon: Icons.room_service_outlined,
      emptyTitle: 'Chưa có món nào sẵn sàng',
      emptyMessage: 'Món bếp làm xong sẽ hiện ở đây để mang ra bàn.',
      itemBuilder: (context, item) => _ReadyItemCard(item: item),
    );
  }
}

class _ReadyItemCard extends ConsumerWidget {
  const _ReadyItemCard({required this.item});

  final KitchenItem item;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final readyAt = item.readyAt;
    return AppCard(
      child: Row(
        children: <Widget>[
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            decoration: BoxDecoration(
              color: AppColors.successSoft,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              item.tableCode,
              style: const TextStyle(
                color: AppColors.success,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  '${item.quantity}× ${item.productName}',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                if (item.optionSummary.isNotEmpty)
                  Text(
                    item.optionSummary,
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 12.5,
                    ),
                  ),
                if (readyAt != null)
                  Text(
                    'Xong ${formatElapsed(readyAt, DateTime.now())} trước',
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 12.5,
                    ),
                  ),
              ],
            ),
          ),
          FilledButton(
            onPressed: () async {
              final ok = await runGuarded(
                context,
                () => ref.read(ordersRepositoryProvider).serveItem(item.itemId),
                onError: (_) async {
                  await ref.read(readyItemsProvider.notifier).reload();
                },
              );
              if (ok) await ref.read(readyItemsProvider.notifier).reload();
            },
            child: const Text('Đã mang ra'),
          ),
        ],
      ),
    );
  }
}

// ------------------------------------------------------------------ Yêu cầu hủy

class _CancelRequestsTab extends ConsumerWidget {
  const _CancelRequestsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return LiveListView<CancelRequest>(
      value: ref.watch(cancelRequestsProvider),
      onRefresh: () => _safeReload(
        context,
        ref.read(cancelRequestsProvider.notifier).reload,
      ),
      onRetry: () => ref.invalidate(cancelRequestsProvider),
      emptyIcon: Icons.playlist_add_check_rounded,
      emptyTitle: 'Không có yêu cầu hủy nào',
      emptyMessage: 'Khách xin hủy món đang nấu sẽ hiện ở đây để bạn duyệt.',
      itemBuilder: (context, request) => _CancelRequestCard(request: request),
    );
  }
}

class _CancelRequestCard extends ConsumerWidget {
  const _CancelRequestCard({required this.request});

  final CancelRequest request;

  Future<void> _reload(WidgetRef ref) async {
    try {
      await ref.read(cancelRequestsProvider.notifier).reload();
    } on ApiException {
      // Đã báo lỗi ở thao tác chính.
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final repo = ref.read(ordersRepositoryProvider);
    final requestedAt = request.requestedAt;
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              Text(
                request.tableCode,
                style: const TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              StatusPill(
                label: request.currentItemStatus.label,
                foreground: AppColors.info,
                background: AppColors.infoSoft,
              ),
              const Spacer(),
              if (requestedAt != null)
                Text(
                  formatElapsed(requestedAt, DateTime.now()),
                  style: const TextStyle(
                    color: AppColors.muted,
                    fontSize: 12.5,
                  ),
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            '${request.quantity}× ${request.productName}',
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          if ((request.requestReason ?? '').isNotEmpty)
            Text(
              'Lý do khách: ${request.requestReason}',
              style: const TextStyle(color: AppColors.muted),
            ),
          const SizedBox(height: AppSpacing.xs),
          const Text(
            'Duyệt hủy: món bị hủy và tính là hao hụt (không tính tiền).',
            style: TextStyle(color: AppColors.muted, fontSize: 12.5),
          ),
          const SizedBox(height: AppSpacing.md),
          Row(
            children: <Widget>[
              Expanded(
                child: OutlinedButton(
                  onPressed: () async {
                    final reason = await showRequiredTextDialog(
                      context,
                      title: 'Từ chối yêu cầu hủy',
                      label: 'Lý do từ chối',
                      confirmLabel: 'Từ chối',
                    );
                    if (reason == null || !context.mounted) return;
                    final ok = await runGuarded(
                      context,
                      () => repo.denyCancel(request.id, reason: reason),
                      success: 'Đã từ chối yêu cầu hủy.',
                    );
                    if (ok || context.mounted) unawaited(_reload(ref));
                  },
                  child: const Text('Từ chối'),
                ),
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: FilledButton(
                  onPressed: () async {
                    final sure = await confirmDialog(
                      context,
                      title: 'Duyệt hủy món?',
                      message:
                          '${request.quantity}× ${request.productName} sẽ bị hủy và ghi nhận hao hụt.',
                      confirmLabel: 'Duyệt hủy',
                      destructive: true,
                    );
                    if (!sure || !context.mounted) return;
                    await runGuarded(
                      context,
                      () => repo.approveCancel(request.id),
                      success: 'Đã duyệt hủy món.',
                    );
                    unawaited(_reload(ref));
                  },
                  child: const Text('Duyệt hủy'),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
