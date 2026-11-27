import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/util/format.dart';
import '../../../shared/widgets/state_views.dart';
import '../application/billing_providers.dart';
import '../domain/billing_models.dart';

/// Biên lai hoàn tiền nội bộ `RF-YYYYMMDD-NNNN` của một giao dịch hoàn SUCCESS
/// (`GET /api/v1/refunds/{id}/receipt`). Biên lai nội bộ, không thay thế hóa đơn điện tử.
Future<void> showRefundReceiptDialog(BuildContext context, int refundId) {
  return showDialog<void>(
    context: context,
    builder: (_) => RefundReceiptDialog(refundId: refundId),
  );
}

class RefundReceiptDialog extends ConsumerWidget {
  const RefundReceiptDialog({super.key, required this.refundId});

  final int refundId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final receipt = ref.watch(refundReceiptProvider(refundId));
    return AlertDialog(
      title: const Text('Biên lai hoàn tiền'),
      content: SizedBox(
        width: 400,
        child: receipt.when(
          loading: () => const Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              SkeletonBox(height: 16),
              SizedBox(height: 8),
              SkeletonBox(height: 16),
              SizedBox(height: 8),
              SkeletonBox(width: 160, height: 16),
            ],
          ),
          error: (error, _) {
            // Giao dịch chưa SUCCESS: không có biên lai, thử lại cũng vô ích.
            if (error is ApiException &&
                error.code == ApiErrorCodes.receiptNotAvailable) {
              return InlineBanner(message: error.userMessage);
            }
            return ErrorView(
              error: error,
              title: 'Không tải được biên lai',
              onRetry: () => ref.invalidate(refundReceiptProvider(refundId)),
            );
          },
          data: (data) => _ReceiptBody(receipt: data),
        ),
      ),
      actions: <Widget>[
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Đóng'),
        ),
      ],
    );
  }
}

class _ReceiptBody extends StatelessWidget {
  const _ReceiptBody({required this.receipt});

  final RefundReceipt receipt;

  @override
  Widget build(BuildContext context) {
    final r = receipt;
    return SingleChildScrollView(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Center(
            child: Text(
              r.restaurantName,
              style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
            ),
          ),
          if ((r.address ?? '').isNotEmpty)
            Center(child: Text(r.address!, textAlign: TextAlign.center)),
          if ((r.phone ?? '').isNotEmpty) Center(child: Text(r.phone!)),
          if ((r.taxCode ?? '').isNotEmpty)
            Center(child: Text('MST: ${r.taxCode}')),
          const Divider(height: AppSpacing.xl),
          Text(
            'Biên lai ${r.receiptNumber}',
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          Text(
            'Hóa đơn ${r.billNumber} · Bàn ${r.tableCode}'
            '${(r.tableName ?? '').isNotEmpty ? ' (${r.tableName})' : ''}',
          ),
          Text('Trạng thái hóa đơn: ${r.billStatus.label}'),
          if (r.issuedAt != null) Text(formatDayClock(r.issuedAt!)),
          if ((r.managerName ?? '').isNotEmpty)
            Text('Manager: ${r.managerName}'),
          const Divider(height: AppSpacing.xl),
          Text('Lý do hoàn: ${r.reason}'),
          if ((r.voidReason ?? '').isNotEmpty)
            Text(
              'Lý do Void: ${r.voidReason}',
              style: const TextStyle(color: AppColors.muted),
            ),
          const Divider(height: AppSpacing.xl),
          _MoneyLine('Tổng hóa đơn', r.billTotalAmount),
          _MoneyLine('Đã thu thành công', r.totalPaidSuccess),
          _MoneyLine('Đã hoàn trước đó', r.refundedBefore),
          _MoneyLine(
            'Hoàn lần này (${r.method.label})',
            r.amount,
            strong: true,
          ),
          _MoneyLine('Đã hoàn tổng cộng', r.refundedAfter),
          _MoneyLine(
            'Còn phải hoàn',
            r.remainingToRefund,
            strong: true,
            color: r.isFullyRefunded ? AppColors.success : AppColors.danger,
          ),
          if (r.footer.isNotEmpty) ...<Widget>[
            const SizedBox(height: AppSpacing.md),
            Center(
              child: Text(
                r.footer,
                textAlign: TextAlign.center,
                style: const TextStyle(color: AppColors.muted, fontSize: 12.5),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _MoneyLine extends StatelessWidget {
  const _MoneyLine(this.label, this.amount, {this.strong = false, this.color});

  final String label;
  final int amount;
  final bool strong;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final style = TextStyle(
      fontWeight: strong ? FontWeight.w700 : FontWeight.w400,
      color: color,
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: <Widget>[
          Expanded(child: Text(label, style: style)),
          Text(formatVnd(amount), style: style),
        ],
      ),
    );
  }
}
