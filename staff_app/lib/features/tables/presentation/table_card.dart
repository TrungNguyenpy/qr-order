import 'package:flutter/material.dart';

import '../../../app/theme.dart';
import '../../../core/util/format.dart';
import '../../../shared/widgets/app_card.dart';
import '../domain/table_models.dart';
import 'table_style.dart';

/// Ô bàn trên sơ đồ: mã bàn, tên, trạng thái, thời gian phiên và cờ cần chú ý.
class TableCard extends StatelessWidget {
  const TableCard({
    super.key,
    required this.item,
    required this.now,
    required this.onTap,
  });

  final FloorPlanTable item;
  final DateTime now;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final table = item.table;
    final session = item.activeSession;
    final style = styleForTableStatus(table.status);
    final since = session?.openedAt ?? table.statusChangedAt;

    return AppCard(
      onTap: onTap,
      padding: const EdgeInsets.all(10),
      color: AppColors.surface,
      borderColor: session?.needsAttention == true
          ? AppColors.warning
          : AppColors.border,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              Expanded(
                child: Text(
                  table.code,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              if (session?.needsAttention == true)
                const Icon(
                  Icons.notifications_active_rounded,
                  size: 16,
                  color: AppColors.warning,
                ),
            ],
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
          const SizedBox(height: 6),
          Row(
            children: <Widget>[
              const Icon(
                Icons.people_outline_rounded,
                size: 14,
                color: AppColors.muted,
              ),
              const SizedBox(width: 3),
              Text(
                '${table.capacity}',
                style: const TextStyle(color: AppColors.muted, fontSize: 12),
              ),
              const Spacer(),
              if (since != null && table.status != TableStatus.available)
                Text(
                  formatElapsed(since, now),
                  style: const TextStyle(color: AppColors.muted, fontSize: 12),
                ),
            ],
          ),
        ],
      ),
    );
  }
}
