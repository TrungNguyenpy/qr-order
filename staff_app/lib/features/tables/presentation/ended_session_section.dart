import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/util/format.dart';
import '../../../shared/widgets/app_card.dart';
import '../../../shared/widgets/state_views.dart';
import '../../billing/application/billing_providers.dart';
import '../../billing/domain/write_off.dart';
import '../../billing/presentation/cashier_sheet.dart';
import '../../billing/presentation/write_off_card.dart';
import '../../orders/application/session_order_provider.dart';
import '../../orders/domain/order_models.dart';
import '../application/floor_plan_controller.dart';
import '../domain/table_models.dart';

/// Kết quả của phiên vừa kết thúc trên chi tiết bàn (bàn đang dọn): phiên bị đóng
/// cưỡng bức hay đóng bình thường, và với phiên bị đóng cưỡng bức là Order được
/// ghi nhận thất thu (`WRITTEN_OFF`), hủy (`VOIDED`) hay đã thanh toán.
class EndedSessionSection extends ConsumerWidget {
  const EndedSessionSection({
    super.key,
    required this.tableId,
    required this.tableCode,
  });

  final int tableId;
  final String tableCode;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ended = ref.watch(endedSessionProvider(tableId));
    return ended.when(
      loading: () => const AppCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            SkeletonBox(width: 140, height: 16),
            SizedBox(height: 8),
            SkeletonBox(height: 12),
          ],
        ),
      ),
      error: (error, _) => AppCard(
        child: ErrorView(
          error: error,
          title: 'Không tải được kết quả phiên vừa đóng',
          onRetry: () => ref.invalidate(endedSessionProvider(tableId)),
        ),
      ),
      // Empty: bàn chưa có phiên kết thúc nào (ví dụ dữ liệu mới) nên không có gì để hiện.
      data: (session) => session == null
          ? const SizedBox.shrink()
          : EndedSessionCard(session: session, tableCode: tableCode),
    );
  }
}

class EndedSessionCard extends ConsumerWidget {
  const EndedSessionCard({
    super.key,
    required this.session,
    required this.tableCode,
  });

  final StaffSession session;
  final String tableCode;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final forced = session.isForceClosed;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        const Padding(
          padding: EdgeInsets.only(bottom: AppSpacing.sm),
          child: Text(
            'Phiên vừa kết thúc',
            style: TextStyle(
              color: AppColors.muted,
              fontSize: 12.5,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              Row(
                children: <Widget>[
                  const Expanded(
                    child: Text(
                      'Trạng thái phiên',
                      style: TextStyle(color: AppColors.muted),
                    ),
                  ),
                  StatusPill(
                    label: session.status.label,
                    foreground: forced ? AppColors.danger : AppColors.success,
                    background: forced
                        ? AppColors.dangerSoft
                        : AppColors.successSoft,
                  ),
                ],
              ),
              if (session.openedAt != null)
                _Row('Mở lúc', formatDayClock(session.openedAt!)),
              if (session.closedAt != null)
                _Row('Kết thúc lúc', formatDayClock(session.closedAt!)),
              if (forced && (session.closeReason ?? '').isNotEmpty)
                _Row('Lý do đóng', session.closeReason!),
            ],
          ),
        ),
        if (forced) ...<Widget>[
          const SizedBox(height: AppSpacing.sm),
          _ForceCloseOutcome(session: session, tableCode: tableCode),
        ],
      ],
    );
  }
}

class _ForceCloseOutcome extends ConsumerWidget {
  const _ForceCloseOutcome({required this.session, required this.tableCode});

  final StaffSession session;
  final String tableCode;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final order = ref.watch(sessionOrderProvider(session.id));
    return order.when(
      loading: () => const AppCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            SkeletonBox(width: 160, height: 16),
            SizedBox(height: 8),
            SkeletonBox(height: 12),
          ],
        ),
      ),
      error: (error, _) => AppCard(
        child: ErrorView(
          error: error,
          title: 'Không tải được kết quả đóng phiên',
          onRetry: () => ref.invalidate(sessionOrderProvider(session.id)),
        ),
      ),
      data: (data) {
        final orderId = data.orderId;
        if (orderId == null) {
          return const InlineBanner(
            message: 'Phiên chưa có món nào nên không phát sinh thất thu.',
            icon: Icons.info_outline_rounded,
            foreground: AppColors.muted,
            background: AppColors.neutralSoft,
          );
        }
        final bill = ref.watch(billProvider(orderId));
        return bill.when(
          loading: () => const AppCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                SkeletonBox(width: 160, height: 16),
                SizedBox(height: 8),
                SkeletonBox(height: 12),
              ],
            ),
          ),
          error: (error, _) => AppCard(
            child: ErrorView(
              error: error,
              title: 'Không tải được hóa đơn',
              onRetry: () => ref.invalidate(billProvider(orderId)),
            ),
          ),
          data: (billData) {
            final info = WriteOffInfo.resolve(order: data, bill: billData);
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: <Widget>[
                if (info != null)
                  WriteOffCard(info: info)
                else
                  InlineBanner(
                    message: _noWriteOffMessage(data.status),
                    icon: Icons.check_circle_outline_rounded,
                    foreground: AppColors.success,
                    background: AppColors.successSoft,
                  ),
                const SizedBox(height: AppSpacing.sm),
                OutlinedButton.icon(
                  onPressed: () => showCashierSheet(
                    context,
                    sessionId: session.id,
                    tableCode: tableCode,
                  ),
                  icon: const Icon(Icons.receipt_long_rounded, size: 18),
                  label: const Text('Xem ở màn Thu ngân'),
                ),
              ],
            );
          },
        );
      },
    );
  }
}

String _noWriteOffMessage(OrderStatus? status) => switch (status) {
  OrderStatus.voided =>
    'Không có món tính tiền: đơn được hủy (VOIDED), không phát sinh thất thu.',
  OrderStatus.paid => 'Hóa đơn đã thanh toán đủ, không phát sinh thất thu.',
  _ => 'Không ghi nhận thất thu cho phiên này.',
};

class _Row extends StatelessWidget {
  const _Row(this.label, this.value);

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(label, style: const TextStyle(color: AppColors.muted)),
          const SizedBox(width: AppSpacing.md),
          Expanded(
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
