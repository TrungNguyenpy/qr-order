import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import 'app_card.dart';
import 'state_views.dart';

/// Khung chung cho một danh sách trực tuyến: Loading / Empty / Error / Success.
class LiveListView<T> extends StatelessWidget {
  const LiveListView({super.key, 
    required this.value,
    required this.onRefresh,
    required this.onRetry,
    required this.emptyTitle,
    required this.emptyMessage,
    required this.emptyIcon,
    required this.itemBuilder,
  });

  final AsyncValue<List<T>> value;
  final Future<void> Function() onRefresh;
  final VoidCallback onRetry;
  final String emptyTitle;
  final String emptyMessage;
  final IconData emptyIcon;
  final Widget Function(BuildContext context, T item) itemBuilder;

  @override
  Widget build(BuildContext context) {
    return value.when(
      loading: () => const ListSkeleton(),
      error: (error, _) => ErrorView(error: error, onRetry: onRetry),
      data: (items) => RefreshIndicator(
        onRefresh: onRefresh,
        child: items.isEmpty
            ? ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                children: <Widget>[
                  const SizedBox(height: 80),
                  EmptyView(
                    icon: emptyIcon,
                    title: emptyTitle,
                    message: emptyMessage,
                  ),
                ],
              )
            : ListView.separated(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(AppSpacing.lg),
                itemCount: items.length,
                separatorBuilder: (_, __) =>
                    const SizedBox(height: AppSpacing.sm),
                itemBuilder: (context, index) =>
                    itemBuilder(context, items[index]),
              ),
      ),
    );
  }
}

class ListSkeleton extends StatelessWidget {
  const ListSkeleton({super.key});

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
            SkeletonBox(width: 90, height: 16),
            SizedBox(height: 8),
            SkeletonBox(height: 12),
            SizedBox(height: 6),
            SkeletonBox(width: 160, height: 12),
          ],
        ),
      ),
    );
  }
}
