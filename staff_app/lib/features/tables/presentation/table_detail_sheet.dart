import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/providers/core_providers.dart';
import '../../../core/util/format.dart';
import '../../../shared/widgets/app_card.dart';
import '../../../shared/widgets/state_views.dart';
import '../../orders/application/session_order_provider.dart';
import '../../orders/domain/order_models.dart';
import '../../orders/presentation/item_edit_menu.dart';
import '../../orders/presentation/order_actions.dart';
import '../application/floor_plan_controller.dart';
import '../domain/table_models.dart';
import 'table_style.dart';

/// Chi tiết bàn: trạng thái, PIN bàn, order hiện tại và nút xác nhận dọn xong.
Future<void> showTableDetailSheet(
  BuildContext context, {
  required int tableId,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    constraints: const BoxConstraints(maxWidth: 600),
    builder: (_) => _TableDetailSheet(tableId: tableId),
  );
}

class _TableDetailSheet extends ConsumerStatefulWidget {
  const _TableDetailSheet({required this.tableId});

  final int tableId;

  @override
  ConsumerState<_TableDetailSheet> createState() => _TableDetailSheetState();
}

class _TableDetailSheetState extends ConsumerState<_TableDetailSheet> {
  bool _confirming = false;

  FloorPlanTable? _find(List<FloorPlanTable>? tables) {
    if (tables == null) return null;
    for (final item in tables) {
      if (item.id == widget.tableId) return item;
    }
    return null;
  }

  Future<void> _confirmCleaned(FloorPlanTable item) async {
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    setState(() => _confirming = true);
    try {
      await ref
          .read(tablesRepositoryProvider)
          .confirmCleaned(tableId: item.id, version: item.table.version);
      try {
        await ref.read(floorPlanProvider.notifier).reload();
      } on ApiException {
        // Dữ liệu sẽ được làm mới bởi realtime/thăm dò; việc dọn bàn đã thành công.
      }
      messenger.showSnackBar(
        SnackBar(
          content: Text('Bàn ${item.table.code} đã sẵn sàng đón khách.'),
        ),
      );
      if (navigator.mounted) navigator.pop();
    } on ApiException catch (error) {
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(content: Text(error.userMessage)));
      // Xung đột phiên bản / sai trạng thái: dữ liệu trên máy đã cũ, tải lại ngay.
      if (error.isConcurrentModification ||
          error.code == ApiErrorCodes.invalidTableTransition) {
        try {
          await ref.read(floorPlanProvider.notifier).reload();
        } on ApiException {
          // Bỏ qua: đã báo lỗi chính ở trên.
        }
      }
    } finally {
      if (mounted) setState(() => _confirming = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final plan = ref.watch(floorPlanProvider);
    final item = _find(plan.valueOrNull);

    // Có thay đổi từ máy chủ (realtime/tải lại): làm mới order của phiên đang xem.
    ref.listen<AsyncValue<List<FloorPlanTable>>>(floorPlanProvider, (_, next) {
      final session = _find(next.valueOrNull)?.activeSession;
      if (session != null) ref.invalidate(sessionOrderProvider(session.id));
    });

    if (item == null) {
      return const SizedBox(
        height: 220,
        child: EmptyView(
          icon: Icons.table_restaurant_outlined,
          title: 'Không tìm thấy bàn',
          message: 'Bàn có thể đã bị ngừng sử dụng. Hãy làm mới sơ đồ bàn.',
        ),
      );
    }

    final table = item.table;
    final session = item.activeSession;
    final style = styleForTableStatus(table.status);

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
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      table.code,
                      style: const TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    Text(
                      table.displayName,
                      style: const TextStyle(color: AppColors.muted),
                    ),
                  ],
                ),
              ),
              StatusPill(
                label: table.status.label,
                foreground: style.foreground,
                background: style.background,
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.md),
          AppCard(
            child: Column(
              children: <Widget>[
                _InfoRow('Khu vực', table.area ?? 'Chưa phân khu'),
                _InfoRow('Sức chứa', '${table.capacity} chỗ'),
                if (table.statusChangedAt != null)
                  _InfoRow(
                    'Đổi trạng thái lúc',
                    formatDayClock(table.statusChangedAt!),
                  ),
              ],
            ),
          ),
          if (table.status == TableStatus.cleaning) ...<Widget>[
            const SizedBox(height: AppSpacing.md),
            FilledButton.icon(
              onPressed: _confirming ? null : () => _confirmCleaned(item),
              icon: _confirming
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : const Icon(Icons.cleaning_services_rounded, size: 18),
              label: const Text('Xác nhận dọn xong'),
            ),
          ],
          if (session != null) ...<Widget>[
            const SizedBox(height: AppSpacing.lg),
            _SessionSection(sessionId: session.id),
            const SizedBox(height: AppSpacing.lg),
            _OrderSection(sessionId: session.id, tableCode: table.code),
          ] else if (table.status == TableStatus.available) ...<Widget>[
            const SizedBox(height: AppSpacing.lg),
            const InlineBanner(
              message:
                  'Bàn đang trống. Khách quét QR tại bàn để mở phiên gọi món.',
              icon: Icons.info_outline_rounded,
              foreground: AppColors.muted,
              background: AppColors.neutralSoft,
            ),
          ],
        ],
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow(this.label, this.value);

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        children: <Widget>[
          Text(label, style: const TextStyle(color: AppColors.muted)),
          const Spacer(),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: Text(
        text,
        style: const TextStyle(
          color: AppColors.muted,
          fontSize: 12.5,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _SessionSection extends ConsumerWidget {
  const _SessionSection({required this.sessionId});

  final int sessionId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(staffSessionProvider(sessionId));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        const _SectionHeader('Phiên gọi món'),
        session.when(
          loading: () => const AppCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                SkeletonBox(width: 120, height: 18),
                SizedBox(height: 8),
                SkeletonBox(height: 12),
              ],
            ),
          ),
          error: (error, _) => AppCard(
            child: ErrorView(
              error: error,
              title: 'Không tải được phiên',
              onRetry: () => ref.invalidate(staffSessionProvider(sessionId)),
            ),
          ),
          data: (data) => AppCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: <Widget>[
                _InfoRow('Trạng thái phiên', data.status.label),
                if (data.openedAt != null)
                  _InfoRow('Mở lúc', formatDayClock(data.openedAt!)),
                _InfoRow('Thiết bị đang dùng', '${data.deviceCount}'),
                _InfoRow(
                  'Xác nhận đợt đầu',
                  data.isVerified ? 'Đã xác nhận' : 'Chưa xác nhận',
                ),
                const Divider(height: AppSpacing.xl),
                const Text(
                  'Mã PIN bàn (đọc cho khách khi cần xác nhận)',
                  style: TextStyle(color: AppColors.muted, fontSize: 12.5),
                ),
                const SizedBox(height: 4),
                if (data.hasPin)
                  SelectableText(
                    data.tablePin!,
                    style: const TextStyle(
                      fontSize: 28,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 6,
                    ),
                  )
                else
                  const Text(
                    'Chưa có PIN',
                    style: TextStyle(color: AppColors.muted),
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _OrderSection extends ConsumerWidget {
  const _OrderSection({required this.sessionId, required this.tableCode});

  final int sessionId;
  final String tableCode;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final order = ref.watch(sessionOrderProvider(sessionId));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        const _SectionHeader('Món đã gọi'),
        OrderActions(
          sessionId: sessionId,
          tableCode: tableCode,
          orderStatus: order.valueOrNull?.status,
          loaded: order.hasValue,
        ),
        order.when(
          loading: () => const AppCard(
            child: Column(
              children: <Widget>[
                SkeletonBox(height: 14),
                SizedBox(height: 8),
                SkeletonBox(height: 14),
                SizedBox(height: 8),
                SkeletonBox(width: 140, height: 14),
              ],
            ),
          ),
          error: (error, _) => AppCard(
            child: ErrorView(
              error: error,
              title: 'Không tải được món đã gọi',
              onRetry: () => ref.invalidate(sessionOrderProvider(sessionId)),
            ),
          ),
          data: (data) {
            if (data.isEmpty) {
              return const AppCard(
                child: EmptyView(
                  icon: Icons.restaurant_menu_rounded,
                  title: 'Khách chưa gọi món',
                ),
              );
            }
            return AppCard(
              padding: EdgeInsets.zero,
              child: Column(
                children: <Widget>[
                  for (final batch in data.batches)
                    _BatchBlock(
                      batch: batch,
                      sessionId: sessionId,
                      editable: data.status == OrderStatus.open,
                    ),
                  Padding(
                    padding: const EdgeInsets.all(AppSpacing.md),
                    child: Row(
                      children: <Widget>[
                        const Text(
                          'Tạm tính',
                          style: TextStyle(color: AppColors.muted),
                        ),
                        const Spacer(),
                        Text(
                          formatVnd(data.billableTotal),
                          style: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ],
    );
  }
}

class _BatchBlock extends StatelessWidget {
  const _BatchBlock({
    required this.batch,
    required this.sessionId,
    required this.editable,
  });

  final OrderBatch batch;
  final int sessionId;
  final bool editable;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Container(
          color: AppColors.neutralSoft,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          child: Row(
            children: <Widget>[
              Text(
                'Đợt ${batch.seq}',
                style: const TextStyle(
                  fontWeight: FontWeight.w600,
                  fontSize: 13,
                ),
              ),
              const SizedBox(width: 8),
              if (batch.submittedAt != null)
                Text(
                  formatClock(batch.submittedAt!),
                  style: const TextStyle(color: AppColors.muted, fontSize: 12),
                ),
              const Spacer(),
              Text(
                batch.status.label,
                style: const TextStyle(color: AppColors.muted, fontSize: 12),
              ),
            ],
          ),
        ),
        for (final item in batch.items)
          _ItemRow(item: item, sessionId: sessionId, editable: editable),
      ],
    );
  }
}

class _ItemRow extends ConsumerWidget {
  const _ItemRow({
    required this.item,
    required this.sessionId,
    required this.editable,
  });

  final OrderItem item;
  final int sessionId;
  final bool editable;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final canEdit =
        editable &&
        !item.hasPendingCancelRequest &&
        (item.status == OrderItemStatus.pending ||
            item.status == OrderItemStatus.confirmed);
    final struck =
        item.status == OrderItemStatus.cancelled ||
        item.status == OrderItemStatus.rejected;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          SizedBox(
            width: 28,
            child: Text(
              '${item.quantity}×',
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  item.productName,
                  style: TextStyle(
                    decoration: struck ? TextDecoration.lineThrough : null,
                    color: struck ? AppColors.muted : AppColors.text,
                  ),
                ),
                if (item.options.isNotEmpty)
                  Text(
                    item.options.map((o) => o.optionName).join(', '),
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 12,
                    ),
                  ),
                if ((item.note ?? '').isNotEmpty)
                  Text(
                    'Ghi chú: ${item.note}',
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 12,
                    ),
                  ),
                const SizedBox(height: 2),
                Text(
                  item.hasPendingCancelRequest
                      ? '${item.status.label} · khách xin hủy'
                      : item.status.label,
                  style: TextStyle(
                    fontSize: 12,
                    color: item.hasPendingCancelRequest
                        ? AppColors.warning
                        : AppColors.muted,
                  ),
                ),
              ],
            ),
          ),
          Text(
            formatVnd(item.lineTotal),
            style: TextStyle(color: struck ? AppColors.muted : AppColors.text),
          ),
          if (canEdit)
            ItemEditMenu(item: item, sessionId: sessionId)
          else
            const SizedBox(width: 8),
        ],
      ),
    );
  }
}
