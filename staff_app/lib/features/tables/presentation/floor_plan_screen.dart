import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/network/api_exception.dart';
import '../../../shared/widgets/state_views.dart';
import '../application/floor_plan_controller.dart';
import '../domain/table_filters.dart';
import '../domain/table_models.dart';
import 'table_card.dart';
import 'table_detail_sheet.dart';

/// Sơ đồ bàn: lưới theo khu vực, bộ lọc trạng thái, tìm kiếm, cập nhật realtime.
class FloorPlanScreen extends ConsumerStatefulWidget {
  const FloorPlanScreen({super.key});

  @override
  ConsumerState<FloorPlanScreen> createState() => _FloorPlanScreenState();
}

class _FloorPlanScreenState extends ConsumerState<FloorPlanScreen> {
  Timer? _clock;
  DateTime _now = DateTime.now();

  @override
  void initState() {
    super.initState();
    // Cập nhật nhãn "đã ngồi bao lâu" mỗi phút.
    _clock = Timer.periodic(const Duration(minutes: 1), (_) {
      if (mounted) setState(() => _now = DateTime.now());
    });
  }

  @override
  void dispose() {
    _clock?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      await ref.read(floorPlanProvider.notifier).reload();
    } on ApiException catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(error.userMessage)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final plan = ref.watch(floorPlanProvider);
    final filter = ref.watch(tableFilterProvider);
    final query = ref.watch(tableSearchProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Sơ đồ bàn'),
        actions: <Widget>[
          IconButton(
            tooltip: 'Làm mới',
            onPressed: _refresh,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: plan.when(
        loading: () => const _FloorPlanSkeleton(),
        error: (error, _) => ErrorView(
          error: error,
          onRetry: () => ref.invalidate(floorPlanProvider),
        ),
        data: (tables) => _FloorPlanBody(
          tables: tables,
          filter: filter,
          query: query,
          now: _now,
          refreshing: plan.isRefreshing,
          onRefresh: _refresh,
        ),
      ),
    );
  }
}

class _FloorPlanBody extends ConsumerWidget {
  const _FloorPlanBody({
    required this.tables,
    required this.filter,
    required this.query,
    required this.now,
    required this.refreshing,
    required this.onRefresh,
  });

  final List<FloorPlanTable> tables;
  final TableFilter filter;
  final String query;
  final DateTime now;
  final bool refreshing;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final counts = countByFilter(tables);
    final visible = filterTables(tables, filter: filter, query: query);
    final groups = groupByArea(visible);

    return Column(
      children: <Widget>[
        _FilterBar(counts: counts, selected: filter),
        Expanded(
          child: RefreshIndicator(
            onRefresh: onRefresh,
            child: tables.isEmpty
                ? ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    children: const <Widget>[
                      SizedBox(height: 80),
                      EmptyView(
                        icon: Icons.table_restaurant_outlined,
                        title: 'Chưa có bàn nào',
                        message:
                            'Quản trị viên cần tạo bàn trong Admin Dashboard.',
                      ),
                    ],
                  )
                : visible.isEmpty
                ? ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    children: <Widget>[
                      const SizedBox(height: 80),
                      EmptyView(
                        icon: Icons.search_off_rounded,
                        title: 'Không có bàn phù hợp',
                        message: query.isEmpty
                            ? 'Không có bàn nào ở trạng thái "${filter.label}".'
                            : 'Không tìm thấy bàn khớp "$query".',
                        action: OutlinedButton(
                          onPressed: () {
                            ref
                                .read(tableFilterProvider.notifier)
                                .select(TableFilter.all);
                            ref.read(tableSearchProvider.notifier).set('');
                          },
                          child: const Text('Xóa bộ lọc'),
                        ),
                      ),
                    ],
                  )
                : CustomScrollView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    slivers: <Widget>[
                      for (final group in groups) ...<Widget>[
                        SliverToBoxAdapter(
                          child: Padding(
                            padding: const EdgeInsets.fromLTRB(
                              AppSpacing.lg,
                              AppSpacing.md,
                              AppSpacing.lg,
                              AppSpacing.sm,
                            ),
                            child: Text(
                              '${group.area ?? 'Chưa phân khu'} · ${group.tables.length}',
                              style: const TextStyle(
                                color: AppColors.muted,
                                fontSize: 12.5,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                        ),
                        SliverPadding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: AppSpacing.lg,
                          ),
                          sliver: SliverGrid(
                            gridDelegate:
                                const SliverGridDelegateWithMaxCrossAxisExtent(
                                  maxCrossAxisExtent: 168,
                                  mainAxisExtent: 112,
                                  mainAxisSpacing: AppSpacing.sm,
                                  crossAxisSpacing: AppSpacing.sm,
                                ),
                            delegate: SliverChildBuilderDelegate((
                              context,
                              index,
                            ) {
                              final item = group.tables[index];
                              return TableCard(
                                item: item,
                                now: now,
                                onTap: () => showTableDetailSheet(
                                  context,
                                  tableId: item.id,
                                ),
                              );
                            }, childCount: group.tables.length),
                          ),
                        ),
                      ],
                      const SliverToBoxAdapter(
                        child: SizedBox(height: AppSpacing.xl),
                      ),
                    ],
                  ),
          ),
        ),
      ],
    );
  }
}

class _FilterBar extends ConsumerWidget {
  const _FilterBar({required this.counts, required this.selected});

  final Map<TableFilter, int> counts;
  final TableFilter selected;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.surface,
        border: Border(bottom: BorderSide(color: AppColors.border)),
      ),
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.lg,
        AppSpacing.sm,
        AppSpacing.lg,
        AppSpacing.sm,
      ),
      child: Column(
        children: <Widget>[
          SizedBox(
            height: 40,
            child: TextField(
              onChanged: ref.read(tableSearchProvider.notifier).set,
              decoration: const InputDecoration(
                hintText: 'Tìm mã bàn, tên bàn, khu vực',
                prefixIcon: Icon(Icons.search_rounded, size: 20),
              ),
            ),
          ),
          const SizedBox(height: AppSpacing.sm),
          SizedBox(
            height: 32,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: TableFilter.values.length,
              separatorBuilder: (_, __) => const SizedBox(width: AppSpacing.sm),
              itemBuilder: (context, index) {
                final filter = TableFilter.values[index];
                return ChoiceChip(
                  label: Text('${filter.label} ${counts[filter] ?? 0}'),
                  selected: filter == selected,
                  onSelected: (_) =>
                      ref.read(tableFilterProvider.notifier).select(filter),
                  showCheckmark: false,
                  labelStyle: TextStyle(
                    fontSize: 12.5,
                    color: filter == selected
                        ? AppColors.primary
                        : AppColors.text,
                    fontWeight: FontWeight.w600,
                  ),
                  selectedColor: AppColors.primarySoft,
                  backgroundColor: AppColors.surface,
                  side: BorderSide(
                    color: filter == selected
                        ? AppColors.primary
                        : AppColors.border,
                  ),
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _FloorPlanSkeleton extends StatelessWidget {
  const _FloorPlanSkeleton();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(AppSpacing.lg),
      child: GridView.builder(
        physics: const NeverScrollableScrollPhysics(),
        gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
          maxCrossAxisExtent: 168,
          mainAxisExtent: 112,
          mainAxisSpacing: AppSpacing.sm,
          crossAxisSpacing: AppSpacing.sm,
        ),
        itemCount: 12,
        itemBuilder: (_, __) => const DecoratedBox(
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.all(Radius.circular(10)),
            border: Border.fromBorderSide(BorderSide(color: AppColors.border)),
          ),
          child: Padding(
            padding: EdgeInsets.all(10),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                SkeletonBox(width: 48, height: 16),
                SizedBox(height: 6),
                SkeletonBox(width: 80, height: 11),
                Spacer(),
                SkeletonBox(width: 70, height: 16, radius: 999),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
