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
import '../../orders/domain/order_action_models.dart';
import '../../orders/domain/order_models.dart';
import '../application/kitchen_controller.dart';

/// Màn hình bếp (KDS): món chờ nấu và đang nấu, cũ trước, cập nhật realtime.
/// Màn hình rộng hiện hai cột; điện thoại hiện hai tab.
class KitchenScreen extends ConsumerStatefulWidget {
  const KitchenScreen({super.key});

  @override
  ConsumerState<KitchenScreen> createState() => _KitchenScreenState();
}

class _KitchenScreenState extends ConsumerState<KitchenScreen> {
  Timer? _clock;
  DateTime _now = DateTime.now();

  @override
  void initState() {
    super.initState();
    // Cập nhật thời gian chờ mỗi 20 giây.
    _clock = Timer.periodic(const Duration(seconds: 20), (_) {
      if (mounted) setState(() => _now = DateTime.now());
    });
  }

  @override
  void dispose() {
    _clock?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(kitchenItemsProvider.notifier).reload();
    } on ApiException catch (error) {
      messenger.showSnackBar(SnackBar(content: Text(error.userMessage)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final items = ref.watch(kitchenItemsProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Bếp'),
        actions: <Widget>[
          IconButton(
            tooltip: 'Làm mới',
            onPressed: _refresh,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: items.when(
        loading: () => const _KitchenSkeleton(),
        error: (error, _) => ErrorView(
          error: error,
          onRetry: () => ref.invalidate(kitchenItemsProvider),
        ),
        data: (data) =>
            _KitchenBody(items: data, now: _now, onRefresh: _refresh),
      ),
    );
  }
}

class _KitchenBody extends StatelessWidget {
  const _KitchenBody({
    required this.items,
    required this.now,
    required this.onRefresh,
  });

  final List<KitchenItem> items;
  final DateTime now;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context) {
    final waiting = items
        .where((item) => item.status == OrderItemStatus.confirmed)
        .toList();
    final cooking = items
        .where((item) => item.status == OrderItemStatus.preparing)
        .toList();

    if (items.isEmpty) {
      return RefreshIndicator(
        onRefresh: onRefresh,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: const <Widget>[
            SizedBox(height: 80),
            EmptyView(
              icon: Icons.soup_kitchen_outlined,
              title: 'Bếp đang rảnh',
              message: 'Món mới được nhân viên xác nhận sẽ hiện ở đây.',
            ),
          ],
        ),
      );
    }

    final waitingList = _ItemColumn(
      items: waiting,
      now: now,
      emptyText: 'Không có món chờ nấu',
      onRefresh: onRefresh,
    );
    final cookingList = _ItemColumn(
      items: cooking,
      now: now,
      emptyText: 'Chưa có món đang nấu',
      onRefresh: onRefresh,
    );

    if (MediaQuery.sizeOf(context).width >= 720) {
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Expanded(
            child: _ColumnShell(
              title: 'Chờ nấu',
              count: waiting.length,
              child: waitingList,
            ),
          ),
          const VerticalDivider(width: 1),
          Expanded(
            child: _ColumnShell(
              title: 'Đang nấu',
              count: cooking.length,
              child: cookingList,
            ),
          ),
        ],
      );
    }

    return DefaultTabController(
      length: 2,
      child: Column(
        children: <Widget>[
          TabBar(
            labelColor: AppColors.primary,
            indicatorColor: AppColors.primary,
            tabs: <Tab>[
              Tab(text: 'Chờ nấu (${waiting.length})'),
              Tab(text: 'Đang nấu (${cooking.length})'),
            ],
          ),
          Expanded(
            child: TabBarView(children: <Widget>[waitingList, cookingList]),
          ),
        ],
      ),
    );
  }
}

class _ColumnShell extends StatelessWidget {
  const _ColumnShell({
    required this.title,
    required this.count,
    required this.child,
  });

  final String title;
  final int count;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Text(
            '$title · $count',
            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
          ),
        ),
        Expanded(child: child),
      ],
    );
  }
}

class _ItemColumn extends StatelessWidget {
  const _ItemColumn({
    required this.items,
    required this.now,
    required this.emptyText,
    required this.onRefresh,
  });

  final List<KitchenItem> items;
  final DateTime now;
  final String emptyText;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: onRefresh,
      child: items.isEmpty
          ? ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              children: <Widget>[
                const SizedBox(height: 60),
                EmptyView(
                  icon: Icons.check_circle_outline_rounded,
                  title: emptyText,
                ),
              ],
            )
          : ListView.separated(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(AppSpacing.md),
              itemCount: items.length,
              separatorBuilder: (_, __) =>
                  const SizedBox(height: AppSpacing.sm),
              itemBuilder: (context, index) =>
                  _KitchenCard(item: items[index], now: now),
            ),
    );
  }
}

/// Màu cảnh báo theo thời gian chờ: >10 phút vàng, >20 phút đỏ.
Color waitingColor(int minutes) {
  if (minutes >= 20) return AppColors.danger;
  if (minutes >= 10) return AppColors.warning;
  return AppColors.muted;
}

class _KitchenCard extends ConsumerStatefulWidget {
  const _KitchenCard({required this.item, required this.now});

  final KitchenItem item;
  final DateTime now;

  @override
  ConsumerState<_KitchenCard> createState() => _KitchenCardState();
}

class _KitchenCardState extends ConsumerState<_KitchenCard> {
  bool _busy = false;

  Future<void> _afterAction() async {
    try {
      await ref.read(kitchenItemsProvider.notifier).reload();
    } on ApiException {
      // Realtime/thăm dò sẽ làm mới.
    }
  }

  Future<void> _advance(OrderItemStatus target) async {
    setState(() => _busy = true);
    await runGuarded(
      context,
      () => ref
          .read(ordersRepositoryProvider)
          .advanceKitchenItem(widget.item.itemId, target),
      onError: (_) => _afterAction(),
    );
    await _afterAction();
    if (mounted) setState(() => _busy = false);
  }

  Future<void> _reject() async {
    final item = widget.item;
    final reason = await showRejectReasonDialog(
      context,
      title: 'Từ chối món',
      subtitle: item.status == OrderItemStatus.preparing
          ? '${item.quantity}× ${item.productName} (đang nấu: tính là hao hụt)'
          : '${item.quantity}× ${item.productName}',
    );
    if (reason == null || !mounted) return;
    setState(() => _busy = true);
    await runGuarded(
      context,
      () => ref
          .read(ordersRepositoryProvider)
          .rejectKitchenItem(
            item.itemId,
            reasonCode: reason.code,
            reasonNote: reason.note,
          ),
      success: 'Đã từ chối món.',
      onError: (_) => _afterAction(),
    );
    await _afterAction();
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    final since = item.confirmedAt;
    // Thời gian chờ: ưu tiên đồng hồ máy chủ khi tải, cộng thêm phần trôi qua.
    final minutes = since != null
        ? widget.now.difference(since).inMinutes.clamp(0, 100000).toInt()
        : item.waitingSeconds ~/ 60;
    final cooking = item.status == OrderItemStatus.preparing;

    return AppCard(
      borderColor: cooking ? AppColors.info : AppColors.border,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              Text(
                item.tableCode,
                style: const TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const Spacer(),
              Icon(
                Icons.timer_outlined,
                size: 16,
                color: waitingColor(minutes),
              ),
              const SizedBox(width: 4),
              Text(
                since == null
                    ? '$minutes phút'
                    : formatElapsed(since, widget.now),
                style: TextStyle(
                  color: waitingColor(minutes),
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            '${item.quantity}× ${item.productName}',
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
          ),
          if (item.optionSummary.isNotEmpty)
            Text(
              item.optionSummary,
              style: const TextStyle(color: AppColors.muted),
            ),
          if ((item.note ?? '').isNotEmpty)
            Container(
              margin: const EdgeInsets.only(top: 6),
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: AppColors.warningSoft,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                'Ghi chú: ${item.note}',
                style: const TextStyle(color: AppColors.warning),
              ),
            ),
          const SizedBox(height: AppSpacing.md),
          Row(
            children: <Widget>[
              OutlinedButton(
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.danger,
                  minimumSize: const Size(0, 40),
                ),
                onPressed: _busy ? null : _reject,
                child: const Text('Từ chối'),
              ),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: FilledButton(
                  style: FilledButton.styleFrom(minimumSize: const Size(0, 40)),
                  onPressed: _busy
                      ? null
                      : () => _advance(
                          cooking
                              ? OrderItemStatus.ready
                              : OrderItemStatus.preparing,
                        ),
                  child: _busy
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : Text(cooking ? 'Xong, sẵn sàng' : 'Bắt đầu nấu'),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _KitchenSkeleton extends StatelessWidget {
  const _KitchenSkeleton();

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      physics: const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.all(AppSpacing.lg),
      itemCount: 4,
      separatorBuilder: (_, __) => const SizedBox(height: AppSpacing.sm),
      itemBuilder: (_, __) => const AppCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            SkeletonBox(width: 60, height: 20),
            SizedBox(height: 8),
            SkeletonBox(width: 180, height: 16),
            SizedBox(height: 12),
            SkeletonBox(height: 36, radius: 8),
          ],
        ),
      ),
    );
  }
}
