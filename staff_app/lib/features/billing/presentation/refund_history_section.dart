import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/util/format.dart';
import '../../../shared/widgets/app_card.dart';
import '../../../shared/widgets/state_views.dart';
import '../application/billing_providers.dart';
import '../domain/billing_models.dart';
import 'refund_receipt_dialog.dart';

/// Hóa đơn có thể có giao dịch hoàn tiền (đã/đang hoàn hoặc đang chờ hoàn Void).
bool billMayHaveRefunds(Bill bill) =>
    bill.refundedAmount > 0 ||
    bill.refundPendingAmount > 0 ||
    bill.isVoidPending ||
    bill.status == BillStatus.voided;

/// Danh sách giao dịch hoàn tiền của hóa đơn; giao dịch SUCCESS có nút xem biên lai.
class RefundHistorySection extends ConsumerWidget {
  const RefundHistorySection({super.key, required this.billId});

  final int billId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final refunds = ref.watch(billRefundsProvider(billId));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        const Padding(
          padding: EdgeInsets.only(bottom: AppSpacing.sm),
          child: Text(
            'Hoàn tiền',
            style: TextStyle(
              color: AppColors.muted,
              fontSize: 12.5,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        refunds.when(
          loading: () => const AppCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                SkeletonBox(width: 140, height: 14),
                SizedBox(height: 8),
                SkeletonBox(height: 12),
              ],
            ),
          ),
          error: (error, _) => AppCard(
            child: ErrorView(
              error: error,
              title: 'Không tải được lịch sử hoàn tiền',
              onRetry: () => ref.invalidate(billRefundsProvider(billId)),
            ),
          ),
          data: (list) => list.isEmpty
              ? const AppCard(
                  child: Text(
                    'Chưa có giao dịch hoàn tiền.',
                    style: TextStyle(color: AppColors.muted),
                  ),
                )
              : Column(
                  children: <Widget>[
                    for (final refund in list)
                      Padding(
                        padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                        child: RefundCard(refund: refund),
                      ),
                  ],
                ),
        ),
      ],
    );
  }
}

/// Một giao dịch hoàn tiền; SUCCESS có biên lai thì có nút "Xem biên lai".
class RefundCard extends StatelessWidget {
  const RefundCard({super.key, required this.refund});

  final Refund refund;

  @override
  Widget build(BuildContext context) {
    final (Color fg, Color bg) = switch (refund.status) {
      TransactionStatus.success => (AppColors.success, AppColors.successSoft),
      TransactionStatus.pending => (AppColors.warning, AppColors.warningSoft),
      TransactionStatus.failed => (AppColors.danger, AppColors.dangerSoft),
    };
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Row(
            children: <Widget>[
              Expanded(
                child: Text(
                  '${refund.method.label} · ${formatVnd(refund.amount)}',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
              StatusPill(
                label: refund.status.label,
                foreground: fg,
                background: bg,
              ),
            ],
          ),
          if ((refund.receiptNumber ?? '').isNotEmpty)
            Text(
              'Biên lai ${refund.receiptNumber}',
              style: const TextStyle(color: AppColors.muted, fontSize: 12.5),
            ),
          if (refund.reason.isNotEmpty)
            Text(
              'Lý do: ${refund.reason}',
              style: const TextStyle(color: AppColors.muted, fontSize: 12.5),
            ),
          if ((refund.failureReason ?? '').isNotEmpty)
            Text(
              'Thất bại: ${refund.failureReason}',
              style: const TextStyle(color: AppColors.danger, fontSize: 12.5),
            ),
          if (refund.createdAt != null)
            Text(
              formatDayClock(refund.confirmedAt ?? refund.createdAt!),
              style: const TextStyle(color: AppColors.muted, fontSize: 12),
            ),
          if (refund.hasReceipt)
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: () => showRefundReceiptDialog(context, refund.id),
                child: const Text('Xem biên lai'),
              ),
            ),
        ],
      ),
    );
  }
}
