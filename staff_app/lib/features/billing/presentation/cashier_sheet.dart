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
import '../../auth/application/session_controller.dart';
import '../../auth/domain/auth_models.dart';
import '../../orders/application/session_order_provider.dart';
import '../../tables/application/floor_plan_controller.dart';
import '../application/billing_providers.dart';
import '../domain/billing_models.dart';
import '../../orders/domain/order_models.dart';
import 'bill_dialogs.dart';
import 'manager_bill_actions.dart';

/// Thu ngân một bàn: xem order, tính tiền (giảm giá nếu có quyền), thu tiền,
/// xác nhận giao dịch chờ, xem phiếu thanh toán.
Future<void> showCashierSheet(
  BuildContext context, {
  required int sessionId,
  required String tableCode,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    constraints: const BoxConstraints(maxWidth: 680),
    builder: (_) => _CashierSheet(sessionId: sessionId, tableCode: tableCode),
  );
}

class _CashierSheet extends ConsumerWidget {
  const _CashierSheet({required this.sessionId, required this.tableCode});

  final int sessionId;
  final String tableCode;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final order = ref.watch(sessionOrderProvider(sessionId));

    // Dữ liệu bàn đổi (khách gọi thêm, phiên đóng...): làm mới order và Bill.
    ref.listen(floorPlanProvider, (_, __) {
      ref.invalidate(sessionOrderProvider(sessionId));
      final orderId = order.valueOrNull?.orderId;
      if (orderId != null) ref.invalidate(billProvider(orderId));
    });

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
          Text(
            'Thu ngân · $tableCode',
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: AppSpacing.md),
          order.when(
            loading: () => const _SheetSkeleton(),
            error: (error, _) => ErrorView(
              error: error,
              onRetry: () => ref.invalidate(sessionOrderProvider(sessionId)),
            ),
            data: (data) {
              final orderId = data.orderId;
              if (orderId == null || data.isEmpty) {
                return const EmptyView(
                  icon: Icons.restaurant_menu_rounded,
                  title: 'Khách chưa gọi món',
                  message: 'Chưa có gì để tính tiền cho bàn này.',
                );
              }
              return _BillSection(
                orderId: orderId,
                sessionId: sessionId,
                billableTotal: data.billableTotal,
                orderStatus: data.status,
              );
            },
          ),
        ],
      ),
    );
  }
}

class _SheetSkeleton extends StatelessWidget {
  const _SheetSkeleton();

  @override
  Widget build(BuildContext context) {
    return const AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          SkeletonBox(width: 140, height: 18),
          SizedBox(height: 10),
          SkeletonBox(height: 14),
          SizedBox(height: 8),
          SkeletonBox(height: 14),
          SizedBox(height: 8),
          SkeletonBox(width: 200, height: 14),
        ],
      ),
    );
  }
}

class _BillSection extends ConsumerWidget {
  const _BillSection({
    required this.orderId,
    required this.sessionId,
    required this.billableTotal,
    required this.orderStatus,
  });

  final int orderId;
  final int sessionId;
  final int billableTotal;
  final OrderStatus? orderStatus;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bill = ref.watch(billProvider(orderId));
    return bill.when(
      loading: () => const _SheetSkeleton(),
      error: (error, _) => ErrorView(
        error: error,
        title: 'Không tải được hóa đơn',
        onRetry: () => ref.invalidate(billProvider(orderId)),
      ),
      data: (data) {
        // Chưa có hóa đơn, hoặc hóa đơn cũ đã bị hủy và order đã được mở lại: tính tiền mới.
        final needsNewBill =
            data == null ||
            (data.status == BillStatus.voided &&
                orderStatus == OrderStatus.open);
        if (needsNewBill) {
          return _NoBillView(
            orderId: orderId,
            sessionId: sessionId,
            billableTotal: billableTotal,
          );
        }
        return _BillView(
          bill: data,
          sessionId: sessionId,
          orderStatus: orderStatus,
          billableTotal: billableTotal,
        );
      },
    );
  }
}

/// Mọi thay đổi tiền/phiên: làm mới Bill, order và sơ đồ bàn.
Future<void> _refreshAfterMoney(
  WidgetRef ref, {
  required int orderId,
  required int sessionId,
}) async {
  ref.invalidate(billProvider(orderId));
  ref.invalidate(sessionOrderProvider(sessionId));
  try {
    await ref.read(floorPlanProvider.notifier).reload();
  } on ApiException {
    // Sơ đồ bàn sẽ tự làm mới qua realtime.
  }
}

// ------------------------------------------------------------------ Chưa có Bill

class _NoBillView extends ConsumerStatefulWidget {
  const _NoBillView({
    required this.orderId,
    required this.sessionId,
    required this.billableTotal,
  });

  final int orderId;
  final int sessionId;
  final int billableTotal;

  @override
  ConsumerState<_NoBillView> createState() => _NoBillViewState();
}

class _NoBillViewState extends ConsumerState<_NoBillView> {
  bool _busy = false;

  Future<void> _createBill() async {
    final canDiscount =
        ref
            .read(sessionControllerProvider)
            .user
            ?.has(Permission.billDiscount) ??
        false;
    final choice = await showCreateBillDialog(
      context,
      subtotal: widget.billableTotal,
      canDiscount: canDiscount,
    );
    if (choice == null || !mounted) return;
    setState(() => _busy = true);
    await runGuarded(context, () async {
      await ref
          .read(billingRepositoryProvider)
          .createBill(widget.orderId, discount: choice.discount);
    }, success: 'Đã tính tiền. Bàn chuyển sang Chờ thanh toán.');
    if (!mounted) return;
    setState(() => _busy = false);
    // Dù thành công hay lỗi (ví dụ BATCH_PENDING) đều tải lại để thấy trạng thái thật.
    await _refreshAfterMoney(
      ref,
      orderId: widget.orderId,
      sessionId: widget.sessionId,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        AppCard(
          child: Row(
            children: <Widget>[
              const Text(
                'Tạm tính các món đã xác nhận',
                style: TextStyle(color: AppColors.muted),
              ),
              const Spacer(),
              Text(
                formatVnd(widget.billableTotal),
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.sm),
        const Text(
          'Phí phục vụ và VAT được tính khi tạo hóa đơn. Cần xử lý hết đợt chờ xác nhận trước khi tính tiền.',
          style: TextStyle(color: AppColors.muted, fontSize: 12.5),
        ),
        const SizedBox(height: AppSpacing.lg),
        FilledButton.icon(
          onPressed: _busy ? null : _createBill,
          icon: _busy
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Colors.white,
                  ),
                )
              : const Icon(Icons.receipt_long_rounded, size: 18),
          label: const Text('Tính tiền'),
        ),
      ],
    );
  }
}

// ------------------------------------------------------------------ Có Bill

class _BillView extends ConsumerWidget {
  const _BillView({
    required this.bill,
    required this.sessionId,
    required this.orderStatus,
    required this.billableTotal,
  });

  final Bill bill;
  final int sessionId;
  final OrderStatus? orderStatus;
  final int billableTotal;

  Future<void> _record(BuildContext context, WidgetRef ref) async {
    final result = await showRecordPaymentDialog(context, bill: bill);
    if (result == null) return;
    await _refreshAfterMoney(ref, orderId: bill.orderId, sessionId: sessionId);
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          result.sessionClosed
              ? 'Đã thu đủ. Phiên đã đóng, bàn chuyển sang Đang dọn.'
              : result.payment.status == TransactionStatus.pending
              ? 'Đã ghi nhận giao dịch chờ xác nhận.'
              : 'Đã thu ${formatVnd(result.payment.amount)}. Còn ${formatVnd(result.remainingAmount)}.',
        ),
      ),
    );
  }

  Future<void> _confirmPayment(
    BuildContext context,
    WidgetRef ref,
    Payment payment, {
    required bool success,
  }) async {
    String? reason;
    if (!success) {
      reason = await showRequiredTextDialog(
        context,
        title: 'Giao dịch thất bại',
        label: 'Lý do thất bại',
        confirmLabel: 'Ghi nhận thất bại',
      );
      if (reason == null || !context.mounted) return;
    }
    PaymentResult? result;
    final ok = await runGuarded(
      context,
      () async {
        result = await ref
            .read(billingRepositoryProvider)
            .confirmPayment(
              payment.id,
              success: success,
              failureReason: reason,
            );
      },
      onError: (_) async =>
          _refreshAfterMoney(ref, orderId: bill.orderId, sessionId: sessionId),
    );
    if (!ok) return;
    await _refreshAfterMoney(ref, orderId: bill.orderId, sessionId: sessionId);
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          success
              ? (result?.sessionClosed ?? false)
                    ? 'Đã xác nhận. Phiên đã đóng, bàn chuyển sang Đang dọn.'
                    : 'Đã xác nhận giao dịch.'
              : 'Đã ghi nhận giao dịch thất bại.',
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Row(
          children: <Widget>[
            Text(
              bill.billNumber,
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
            const Spacer(),
            StatusPill(
              label: bill.status == BillStatus.active
                  ? bill.paymentStatus.label
                  : bill.status.label,
              foreground: bill.isPaid ? AppColors.success : AppColors.warning,
              background: bill.isPaid
                  ? AppColors.successSoft
                  : AppColors.warningSoft,
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.sm),
        ManagerBillActions(
          bill: bill,
          orderStatus: orderStatus,
          billableTotal: billableTotal,
          onChanged: () => _refreshAfterMoney(
            ref,
            orderId: bill.orderId,
            sessionId: sessionId,
          ),
        ),
        AppCard(
          padding: EdgeInsets.zero,
          child: Column(
            children: <Widget>[
              for (final line in bill.lines) _LineRow(line: line),
              if (bill.lines.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(AppSpacing.md),
                  child: Text(
                    'Không có dòng món.',
                    style: TextStyle(color: AppColors.muted),
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.sm),
        AppCard(
          child: Column(
            children: <Widget>[
              _MoneyRow('Tạm tính', bill.subtotalAmount),
              if (bill.discountAmount > 0)
                _MoneyRow(
                  bill.discountReason == null
                      ? 'Giảm giá'
                      : 'Giảm giá (${bill.discountReason})',
                  -bill.discountAmount,
                ),
              if (bill.serviceFeeAmount > 0)
                _MoneyRow('Phí phục vụ', bill.serviceFeeAmount),
              if (bill.vatAmount > 0) _MoneyRow('VAT', bill.vatAmount),
              const Divider(height: AppSpacing.lg),
              _MoneyRow('Tổng cộng', bill.totalAmount, strong: true),
              _MoneyRow('Đã thu', bill.paidAmount),
              if (bill.pendingAmount > 0)
                _MoneyRow('Chờ xác nhận', bill.pendingAmount),
              _MoneyRow(
                'Còn lại',
                bill.remainingAmount,
                strong: true,
                color: bill.remainingAmount > 0
                    ? AppColors.danger
                    : AppColors.success,
              ),
            ],
          ),
        ),
        if (bill.payments.isNotEmpty) ...<Widget>[
          const SizedBox(height: AppSpacing.md),
          const Text(
            'Giao dịch',
            style: TextStyle(
              color: AppColors.muted,
              fontSize: 12.5,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: AppSpacing.sm),
          for (final payment in bill.payments)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.sm),
              child: _PaymentCard(
                payment: payment,
                onConfirm: () =>
                    _confirmPayment(context, ref, payment, success: true),
                onFail: () =>
                    _confirmPayment(context, ref, payment, success: false),
                onReceipt: () => showReceiptDialog(context, payment.id),
              ),
            ),
        ],
        if (bill.canRecordPayment) ...<Widget>[
          const SizedBox(height: AppSpacing.md),
          FilledButton.icon(
            onPressed: () => _record(context, ref),
            icon: const Icon(Icons.payments_rounded, size: 18),
            label: Text('Thu tiền (còn ${formatVnd(bill.remainingAmount)})'),
          ),
        ] else if (bill.isPaid) ...<Widget>[
          const SizedBox(height: AppSpacing.md),
          const InlineBanner(
            message: 'Hóa đơn đã thanh toán đủ.',
            icon: Icons.check_circle_outline_rounded,
            foreground: AppColors.success,
            background: AppColors.successSoft,
          ),
        ],
      ],
    );
  }
}

class _LineRow extends StatelessWidget {
  const _LineRow({required this.line});

  final BillLine line;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          SizedBox(
            width: 30,
            child: Text(
              '${line.quantity}×',
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(line.productName),
                if (line.options.isNotEmpty)
                  Text(
                    line.options.join(', '),
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 12,
                    ),
                  ),
              ],
            ),
          ),
          Text(formatVnd(line.lineTotal)),
        ],
      ),
    );
  }
}

class _MoneyRow extends StatelessWidget {
  const _MoneyRow(this.label, this.amount, {this.strong = false, this.color});

  final String label;
  final int amount;
  final bool strong;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final style = TextStyle(
      fontWeight: strong ? FontWeight.w700 : FontWeight.w400,
      fontSize: strong ? 16 : 14,
      color: color ?? AppColors.text,
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: <Widget>[
          Expanded(
            child: Text(
              label,
              style: style.copyWith(
                color: strong ? style.color : AppColors.muted,
              ),
            ),
          ),
          Text(formatVnd(amount), style: style),
        ],
      ),
    );
  }
}

class _PaymentCard extends StatelessWidget {
  const _PaymentCard({
    required this.payment,
    required this.onConfirm,
    required this.onFail,
    required this.onReceipt,
  });

  final Payment payment;
  final VoidCallback onConfirm;
  final VoidCallback onFail;
  final VoidCallback onReceipt;

  @override
  Widget build(BuildContext context) {
    final (Color fg, Color bg) = switch (payment.status) {
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
                  '${payment.method.label} · ${formatVnd(payment.amount)}',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
              StatusPill(
                label: payment.status.label,
                foreground: fg,
                background: bg,
              ),
            ],
          ),
          if (payment.receiptNumber != null)
            Text(
              'Phiếu ${payment.receiptNumber}',
              style: const TextStyle(color: AppColors.muted, fontSize: 12.5),
            ),
          if ((payment.failureReason ?? '').isNotEmpty)
            Text(
              'Lý do: ${payment.failureReason}',
              style: const TextStyle(color: AppColors.danger, fontSize: 12.5),
            ),
          if (payment.status == TransactionStatus.pending) ...<Widget>[
            const SizedBox(height: AppSpacing.sm),
            Row(
              children: <Widget>[
                Expanded(
                  child: OutlinedButton(
                    style: OutlinedButton.styleFrom(
                      foregroundColor: AppColors.danger,
                      minimumSize: const Size(0, 40),
                    ),
                    onPressed: onFail,
                    child: const Text('Thất bại'),
                  ),
                ),
                const SizedBox(width: AppSpacing.sm),
                Expanded(
                  child: FilledButton(
                    style: FilledButton.styleFrom(
                      minimumSize: const Size(0, 40),
                    ),
                    onPressed: onConfirm,
                    child: const Text('Đã nhận tiền'),
                  ),
                ),
              ],
            ),
          ] else if (payment.status == TransactionStatus.success) ...<Widget>[
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: onReceipt,
                child: const Text('Xem phiếu'),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
