import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/providers/core_providers.dart';
import '../../../core/util/format.dart';
import '../../../shared/reason_dialog.dart';
import '../../../shared/ui_actions.dart';
import '../../../shared/widgets/app_card.dart';
import '../../../shared/widgets/state_views.dart';
import '../../auth/application/session_controller.dart';
import '../../auth/domain/auth_models.dart';
import '../../orders/domain/order_models.dart';
import '../domain/bill_access.dart';
import '../domain/billing_models.dart';
import 'bill_dialogs.dart';

/// Khối thao tác dành cho Manager / người có quyền tương ứng trên một hóa đơn.
class ManagerBillActions extends ConsumerWidget {
  const ManagerBillActions({
    super.key,
    required this.bill,
    required this.orderStatus,
    required this.billableTotal,
    required this.onChanged,
  });

  final Bill bill;
  final OrderStatus? orderStatus;
  final int billableTotal;

  /// Gọi sau mỗi thao tác thành công để làm mới Bill/order/sơ đồ bàn.
  final Future<void> Function() onChanged;

  Future<void> _void(BuildContext context, WidgetRef ref) async {
    final paid = bill.paidAmount > 0;
    final reason = await showRequiredTextDialog(
      context,
      title: paid ? 'Void hóa đơn đã thu tiền' : 'Void hóa đơn',
      label: 'Lý do Void',
      confirmLabel: 'Void hóa đơn',
    );
    if (reason == null || !context.mounted) return;
    final ok = await runGuarded(
      context,
      () => ref
          .read(billingRepositoryProvider)
          .requestVoid(bill.id, reason: reason),
      success: paid
          ? 'Đã yêu cầu Void. Cần hoàn tiền cho khách để hoàn tất.'
          : 'Đã Void hóa đơn.',
      onError: (_) => onChanged(),
    );
    if (ok) await onChanged();
  }

  Future<void> _withdraw(BuildContext context, WidgetRef ref) async {
    final reason = await showRequiredTextDialog(
      context,
      title: 'Rút yêu cầu Void',
      label: 'Lý do rút Void',
      confirmLabel: 'Rút Void',
    );
    if (reason == null || !context.mounted) return;
    final ok = await runGuarded(
      context,
      () => ref
          .read(billingRepositoryProvider)
          .withdrawVoid(bill.id, reason: reason),
      success: 'Đã rút yêu cầu Void.',
      onError: (_) => onChanged(),
    );
    if (ok) await onChanged();
  }

  Future<void> _refund(BuildContext context) async {
    final result = await showRefundDialog(context, bill: bill);
    if (result == null) return;
    await onChanged();
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          result.voidCompleted
              ? 'Đã hoàn đủ. Hóa đơn chuyển sang Đã Void.'
              : 'Đã ghi nhận hoàn ${formatVnd(result.refund.amount)}. '
                    'Còn ${formatVnd(result.remainingRefundable)}.',
        ),
      ),
    );
  }

  Future<void> _reopen(BuildContext context, WidgetRef ref) async {
    final reason = await showRequiredTextDialog(
      context,
      title: 'Mở lại phiên',
      label: 'Lý do mở lại (ví dụ khách gọi thêm)',
      confirmLabel: 'Mở lại phiên',
    );
    if (reason == null || !context.mounted) return;
    final ok = await runGuarded(
      context,
      () => ref
          .read(billingRepositoryProvider)
          .reopenSession(bill.sessionId, reason: reason),
      success: 'Đã mở lại phiên. Hóa đơn cũ bị hủy, có thể gọi thêm món.',
      onError: (_) => onChanged(),
    );
    if (ok) await onChanged();
  }

  Future<void> _replace(BuildContext context, WidgetRef ref) async {
    final choice = await showCreateBillDialog(
      context,
      subtotal: billableTotal,
      canDiscount:
          ref
              .read(sessionControllerProvider)
              .user
              ?.has(Permission.billDiscount) ??
          false,
    );
    if (choice == null || !context.mounted) return;
    final ok = await runGuarded(
      context,
      () => ref
          .read(billingRepositoryProvider)
          .createReplacementBill(bill.orderId, discount: choice.discount),
      success: 'Đã tạo hóa đơn thay thế.',
      onError: (_) => onChanged(),
    );
    if (ok) await onChanged();
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(sessionControllerProvider).user;
    if (user == null) return const SizedBox.shrink();
    final access = BillActionAccess.of(
      user: user,
      bill: bill,
      orderStatus: orderStatus,
    );
    final notes = <Widget>[
      if (bill.isVoidPending)
        InlineBanner(
          message: bill.needsRefund
              ? 'Đang chờ hoàn tiền: còn ${formatVnd(bill.remainingRefundable)}'
                    '${bill.refundPendingAmount > 0 ? ' (đang chờ xác nhận ${formatVnd(bill.refundPendingAmount)})' : ''}.'
              : 'Đã hoàn đủ hoặc đang chờ xác nhận hoàn tiền.',
        ),
      if (bill.status == BillStatus.voided &&
          orderStatus == OrderStatus.locked &&
          !access.canReplace)
        const InlineBanner(
          message: 'Hóa đơn đã Void, đang chờ Manager tạo hóa đơn thay thế.',
        ),
      if (bill.voidReason != null &&
          bill.status != BillStatus.active &&
          bill.status != BillStatus.writtenOff)
        Text(
          'Lý do Void: ${bill.voidReason}',
          style: const TextStyle(color: AppColors.muted, fontSize: 12.5),
        ),
    ];
    if (!access.any && notes.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        for (final note in notes)
          Padding(
            padding: const EdgeInsets.only(bottom: AppSpacing.sm),
            child: note,
          ),
        if (access.any)
          AppCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: <Widget>[
                const Text(
                  'Thao tác Manager',
                  style: TextStyle(
                    color: AppColors.muted,
                    fontSize: 12.5,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: AppSpacing.sm),
                Wrap(
                  spacing: AppSpacing.sm,
                  runSpacing: AppSpacing.sm,
                  children: <Widget>[
                    if (access.canRefund)
                      FilledButton.icon(
                        onPressed: () => _refund(context),
                        icon: const Icon(Icons.undo_rounded, size: 18),
                        label: const Text('Ghi nhận hoàn tiền'),
                      ),
                    if (access.canReplace)
                      FilledButton.icon(
                        onPressed: () => _replace(context, ref),
                        icon: const Icon(Icons.receipt_long_rounded, size: 18),
                        label: const Text('Tạo hóa đơn thay thế'),
                      ),
                    if (access.canReopen)
                      OutlinedButton.icon(
                        onPressed: () => _reopen(context, ref),
                        icon: const Icon(Icons.lock_open_rounded, size: 18),
                        label: const Text('Mở lại phiên'),
                      ),
                    if (access.canWithdrawVoid)
                      OutlinedButton(
                        onPressed: () => _withdraw(context, ref),
                        child: const Text('Rút Void'),
                      ),
                    if (access.canVoid)
                      OutlinedButton(
                        onPressed: () => _void(context, ref),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: AppColors.danger,
                        ),
                        child: const Text('Void hóa đơn'),
                      ),
                  ],
                ),
              ],
            ),
          ),
      ],
    );
  }
}
