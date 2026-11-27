import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/providers/core_providers.dart';
import '../../../core/util/format.dart';
import '../../../core/util/uuid.dart';
import '../../../shared/widgets/state_views.dart';
import '../application/billing_providers.dart';
import '../data/billing_repository.dart';
import '../domain/billing_models.dart';
import 'bank_transfer_qr.dart';

/// Lựa chọn khi tính tiền: có giảm giá hay không.
class BillChoice {
  const BillChoice(this.discount);

  final DiscountInput? discount;
}

/// Hộp thoại xác nhận tính tiền. Chỉ người có `BILL_DISCOUNT` mới thấy phần giảm giá.
Future<BillChoice?> showCreateBillDialog(
  BuildContext context, {
  required int subtotal,
  required bool canDiscount,
}) {
  return showDialog<BillChoice>(
    context: context,
    builder: (_) =>
        _CreateBillDialog(subtotal: subtotal, canDiscount: canDiscount),
  );
}

class _CreateBillDialog extends StatefulWidget {
  const _CreateBillDialog({required this.subtotal, required this.canDiscount});

  final int subtotal;
  final bool canDiscount;

  @override
  State<_CreateBillDialog> createState() => _CreateBillDialogState();
}

class _CreateBillDialogState extends State<_CreateBillDialog> {
  final GlobalKey<FormState> _formKey = GlobalKey<FormState>();
  final TextEditingController _value = TextEditingController();
  final TextEditingController _reason = TextEditingController();
  bool _discount = false;
  DiscountKind _kind = DiscountKind.percent;

  @override
  void dispose() {
    _value.dispose();
    _reason.dispose();
    super.dispose();
  }

  void _submit() {
    if (_discount) {
      if (!(_formKey.currentState?.validate() ?? false)) return;
      Navigator.of(context).pop(
        BillChoice(
          DiscountInput(kind: _kind, value: _value.text, reason: _reason.text),
        ),
      );
    } else {
      Navigator.of(context).pop(const BillChoice(null));
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Tính tiền'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(
                'Tạm tính ${formatVnd(widget.subtotal)}. Bàn sẽ chuyển sang "Chờ thanh toán" và order bị khóa.',
                style: const TextStyle(color: AppColors.muted),
              ),
              if (widget.canDiscount) ...<Widget>[
                const SizedBox(height: AppSpacing.sm),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Áp dụng giảm giá'),
                  value: _discount,
                  onChanged: (value) => setState(() => _discount = value),
                ),
                if (_discount) ...<Widget>[
                  Wrap(
                    spacing: AppSpacing.sm,
                    children: <Widget>[
                      for (final kind in DiscountKind.values)
                        ChoiceChip(
                          label: Text(kind.label),
                          selected: kind == _kind,
                          onSelected: (_) => setState(() => _kind = kind),
                        ),
                    ],
                  ),
                  const SizedBox(height: AppSpacing.md),
                  TextFormField(
                    controller: _value,
                    keyboardType: const TextInputType.numberWithOptions(
                      decimal: true,
                    ),
                    decoration: InputDecoration(
                      labelText: _kind == DiscountKind.percent
                          ? 'Phần trăm giảm'
                          : 'Số tiền giảm (đ)',
                    ),
                    validator: (value) => validateDiscountValue(
                      _kind,
                      value ?? '',
                      subtotal: widget.subtotal,
                    ),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  TextFormField(
                    controller: _reason,
                    maxLength: 1000,
                    decoration: const InputDecoration(
                      labelText: 'Lý do giảm giá',
                    ),
                    validator: (value) => (value ?? '').trim().isEmpty
                        ? 'Lý do giảm giá là bắt buộc'
                        : null,
                  ),
                ],
              ],
            ],
          ),
        ),
      ),
      actions: <Widget>[
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Hủy'),
        ),
        FilledButton(onPressed: _submit, child: const Text('Tính tiền')),
      ],
    );
  }
}

/// Hộp thoại thu tiền. Trả [PaymentResult] khi ghi nhận thành công, null nếu hủy.
/// `requestId` được giữ nguyên trong suốt vòng đời hộp thoại: bấm lại sau lỗi
/// mạng là gửi lại ĐÚNG giao dịch đó (idempotent), không thu trùng.
Future<PaymentResult?> showRecordPaymentDialog(
  BuildContext context, {
  required Bill bill,
}) {
  return showDialog<PaymentResult>(
    context: context,
    barrierDismissible: false,
    builder: (_) => _RecordPaymentDialog(bill: bill),
  );
}

class _RecordPaymentDialog extends ConsumerStatefulWidget {
  const _RecordPaymentDialog({required this.bill});

  final Bill bill;

  @override
  ConsumerState<_RecordPaymentDialog> createState() =>
      _RecordPaymentDialogState();
}

class _RecordPaymentDialogState extends ConsumerState<_RecordPaymentDialog> {
  final GlobalKey<FormState> _formKey = GlobalKey<FormState>();
  late final TextEditingController _amount = TextEditingController(
    text: widget.bill.remainingAmount.toString(),
  );
  final TextEditingController _note = TextEditingController();
  final String _requestId = generateUuidV4();
  PaymentMethod _method = PaymentMethod.cash;
  bool _receivedNow = true;
  bool _submitting = false;
  ApiException? _error;

  @override
  void initState() {
    super.initState();
    // QR chuyển khoản đổi theo số tiền đang nhập (thu một phần).
    _amount.addListener(_onAmountChanged);
  }

  @override
  void dispose() {
    _amount.removeListener(_onAmountChanged);
    _amount.dispose();
    _note.dispose();
    super.dispose();
  }

  void _onAmountChanged() {
    if (_method == PaymentMethod.bankTransfer && mounted) setState(() {});
  }

  /// Số tiền in trên QR: số đang nhập nếu hợp lệ (1..còn lại), ngược lại là số còn lại.
  int get _qrAmount {
    final typed = int.tryParse(_amount.text.trim());
    final remaining = widget.bill.remainingAmount;
    return typed != null && typed > 0 && typed <= remaining ? typed : remaining;
  }

  String? _validateAmount(String? raw) {
    final amount = int.tryParse((raw ?? '').trim());
    if (amount == null || amount <= 0) return 'Nhập số tiền lớn hơn 0';
    if (amount > widget.bill.remainingAmount) {
      return 'Vượt số còn lại (${formatVnd(widget.bill.remainingAmount)})';
    }
    return null;
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      final result = await ref
          .read(billingRepositoryProvider)
          .recordPayment(
            widget.bill.id,
            requestId: _requestId,
            method: _method,
            amount: int.parse(_amount.text.trim()),
            confirmed: _receivedNow,
            note: _note.text,
          );
      if (!mounted) return;
      Navigator.of(context).pop(result);
    } on ApiException catch (error) {
      if (!mounted) return;
      setState(() {
        _error = error;
        _submitting = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Thu tiền'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(
                'Còn lại ${formatVnd(widget.bill.remainingAmount)}',
                style: const TextStyle(
                  fontWeight: FontWeight.w700,
                  fontSize: 16,
                ),
              ),
              const SizedBox(height: AppSpacing.md),
              if (_error != null) ...<Widget>[
                InlineBanner(
                  message: _error!.userMessage,
                  icon: Icons.error_outline_rounded,
                  foreground: AppColors.danger,
                  background: AppColors.dangerSoft,
                ),
                const SizedBox(height: AppSpacing.md),
              ],
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                children: <Widget>[
                  for (final method in PaymentMethod.values)
                    ChoiceChip(
                      label: Text(method.label),
                      selected: method == _method,
                      onSelected: _submitting
                          ? null
                          : (_) => setState(() => _method = method),
                    ),
                ],
              ),
              const SizedBox(height: AppSpacing.md),
              TextFormField(
                controller: _amount,
                enabled: !_submitting,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Số tiền (đ)'),
                validator: _validateAmount,
              ),
              if (_method == PaymentMethod.bankTransfer) ...<Widget>[
                const SizedBox(height: AppSpacing.md),
                if (widget.bill.bankTransfer case final transfer?)
                  BankTransferQr(info: transfer, amount: _qrAmount)
                else
                  const InlineBanner(
                    message:
                        'Chưa cấu hình tài khoản nhận tiền VietQR. Quản trị viên nhập ở Cấu hình nhà hàng; '
                        'vẫn có thể ghi nhận chuyển khoản thủ công.',
                  ),
              ],
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Đã nhận tiền ngay'),
                subtitle: Text(
                  _receivedNow ? 'Ghi nhận thành công ngay.' : 'Ghi nhận chờ xác nhận (quẹt thẻ/chuyển khoản chưa về).',
                ),
                value: _receivedNow,
                onChanged: _submitting
                    ? null
                    : (value) => setState(() => _receivedNow = value),
              ),
              TextFormField(
                controller: _note,
                enabled: !_submitting,
                maxLength: 500,
                decoration: const InputDecoration(
                  labelText: 'Ghi chú (tùy chọn)',
                ),
              ),
            ],
          ),
        ),
      ),
      actions: <Widget>[
        TextButton(
          onPressed: _submitting ? null : () => Navigator.of(context).pop(),
          child: const Text('Hủy'),
        ),
        FilledButton(
          onPressed: _submitting ? null : _submit,
          child: _submitting
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Colors.white,
                  ),
                )
              : const Text('Ghi nhận'),
        ),
      ],
    );
  }
}

/// Phiếu thanh toán nội bộ của một giao dịch SUCCESS.
Future<void> showReceiptDialog(BuildContext context, int paymentId) {
  return showDialog<void>(
    context: context,
    builder: (_) => _ReceiptDialog(paymentId: paymentId),
  );
}

class _ReceiptDialog extends ConsumerWidget {
  const _ReceiptDialog({required this.paymentId});

  final int paymentId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final receipt = ref.watch(receiptProvider(paymentId));
    return AlertDialog(
      title: const Text('Phiếu thanh toán'),
      content: SizedBox(
        width: 380,
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
          error: (error, _) => ErrorView(
            error: error,
            onRetry: () => ref.invalidate(receiptProvider(paymentId)),
          ),
          data: (data) => SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Center(
                  child: Text(
                    data.restaurantName,
                    style: const TextStyle(
                      fontWeight: FontWeight.w700,
                      fontSize: 16,
                    ),
                  ),
                ),
                if ((data.address ?? '').isNotEmpty)
                  Center(
                    child: Text(data.address!, textAlign: TextAlign.center),
                  ),
                if ((data.phone ?? '').isNotEmpty)
                  Center(child: Text(data.phone!)),
                const Divider(height: AppSpacing.xl),
                Text('Phiếu ${data.receiptNumber}'),
                Text('Hóa đơn ${data.billNumber} · Bàn ${data.tableCode}'),
                if (data.issuedAt != null) Text(formatDayClock(data.issuedAt!)),
                if ((data.cashierName ?? '').isNotEmpty)
                  Text('Thu ngân: ${data.cashierName}'),
                const Divider(height: AppSpacing.xl),
                for (final line in data.lines)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 2),
                    child: Row(
                      children: <Widget>[
                        Expanded(
                          child: Text('${line.quantity}× ${line.productName}'),
                        ),
                        Text(formatVnd(line.lineTotal)),
                      ],
                    ),
                  ),
                const Divider(height: AppSpacing.xl),
                _Row('Tạm tính', data.subtotalAmount),
                if (data.discountAmount > 0)
                  _Row('Giảm giá', -data.discountAmount),
                if (data.serviceFeeAmount > 0)
                  _Row('Phí phục vụ', data.serviceFeeAmount),
                if (data.vatAmount > 0) _Row('VAT', data.vatAmount),
                _Row('Tổng cộng', data.totalAmount, strong: true),
                const Divider(height: AppSpacing.xl),
                _Row('Giao dịch này (${data.method.label})', data.amount),
                _Row('Đã thu tổng cộng', data.paidAfter),
                _Row('Còn lại', data.remainingAfter),
                if (data.footer.isNotEmpty) ...<Widget>[
                  const SizedBox(height: AppSpacing.md),
                  Center(
                    child: Text(
                      data.footer,
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                        color: AppColors.muted,
                        fontSize: 12.5,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
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

class _Row extends StatelessWidget {
  const _Row(this.label, this.amount, {this.strong = false});

  final String label;
  final int amount;
  final bool strong;

  @override
  Widget build(BuildContext context) {
    final style = TextStyle(
      fontWeight: strong ? FontWeight.w700 : FontWeight.w400,
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

/// Hộp thoại ghi nhận hoàn tiền cho hóa đơn VOID_PENDING. Trả [RefundResult]
/// khi thành công, null nếu hủy. `requestId` giữ nguyên suốt vòng đời hộp
/// thoại nên bấm lại sau lỗi mạng không hoàn trùng.
Future<RefundResult?> showRefundDialog(
  BuildContext context, {
  required Bill bill,
}) {
  return showDialog<RefundResult>(
    context: context,
    barrierDismissible: false,
    builder: (_) => _RefundDialog(bill: bill),
  );
}

class _RefundDialog extends ConsumerStatefulWidget {
  const _RefundDialog({required this.bill});

  final Bill bill;

  @override
  ConsumerState<_RefundDialog> createState() => _RefundDialogState();
}

class _RefundDialogState extends ConsumerState<_RefundDialog> {
  final GlobalKey<FormState> _formKey = GlobalKey<FormState>();
  late final TextEditingController _amount = TextEditingController(
    text: widget.bill.remainingRefundable.toString(),
  );
  final TextEditingController _reason = TextEditingController();
  final String _requestId = generateUuidV4();
  PaymentMethod _method = PaymentMethod.cash;
  bool _doneNow = true;
  bool _submitting = false;
  ApiException? _error;

  @override
  void dispose() {
    _amount.dispose();
    _reason.dispose();
    super.dispose();
  }

  String? _validateAmount(String? raw) {
    final amount = int.tryParse((raw ?? '').trim());
    if (amount == null || amount <= 0) return 'Nhập số tiền lớn hơn 0';
    if (amount > widget.bill.remainingRefundable) {
      return 'Vượt số còn phải hoàn (${formatVnd(widget.bill.remainingRefundable)})';
    }
    return null;
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      final result = await ref
          .read(billingRepositoryProvider)
          .recordRefund(
            widget.bill.id,
            requestId: _requestId,
            method: _method,
            amount: int.parse(_amount.text.trim()),
            confirmed: _doneNow,
            reason: _reason.text,
          );
      if (!mounted) return;
      Navigator.of(context).pop(result);
    } on ApiException catch (error) {
      if (!mounted) return;
      setState(() {
        _error = error;
        _submitting = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Hoàn tiền cho khách'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(
                'Còn phải hoàn ${formatVnd(widget.bill.remainingRefundable)}',
                style: const TextStyle(
                  fontWeight: FontWeight.w700,
                  fontSize: 16,
                ),
              ),
              const SizedBox(height: AppSpacing.md),
              if (_error != null) ...<Widget>[
                InlineBanner(
                  message: _error!.userMessage,
                  icon: Icons.error_outline_rounded,
                  foreground: AppColors.danger,
                  background: AppColors.dangerSoft,
                ),
                const SizedBox(height: AppSpacing.md),
              ],
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                children: <Widget>[
                  for (final method in PaymentMethod.values)
                    ChoiceChip(
                      label: Text(method.label),
                      selected: method == _method,
                      onSelected: _submitting
                          ? null
                          : (_) => setState(() => _method = method),
                    ),
                ],
              ),
              const SizedBox(height: AppSpacing.md),
              TextFormField(
                controller: _amount,
                enabled: !_submitting,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: 'Số tiền hoàn (đ)',
                ),
                validator: _validateAmount,
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Đã hoàn tiền xong'),
                subtitle: Text(
                  _doneNow ? 'Ghi nhận thành công ngay.' : 'Ghi nhận chờ xác nhận (chuyển khoản trả lại chưa xong).',
                ),
                value: _doneNow,
                onChanged: _submitting
                    ? null
                    : (value) => setState(() => _doneNow = value),
              ),
              TextFormField(
                controller: _reason,
                enabled: !_submitting,
                maxLength: 1000,
                decoration: const InputDecoration(labelText: 'Lý do hoàn tiền'),
                validator: (value) => (value ?? '').trim().isEmpty
                    ? 'Lý do hoàn tiền là bắt buộc'
                    : null,
              ),
            ],
          ),
        ),
      ),
      actions: <Widget>[
        TextButton(
          onPressed: _submitting ? null : () => Navigator.of(context).pop(),
          child: const Text('Hủy'),
        ),
        FilledButton(
          onPressed: _submitting ? null : _submit,
          child: _submitting
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Colors.white,
                  ),
                )
              : const Text('Ghi nhận hoàn'),
        ),
      ],
    );
  }
}
