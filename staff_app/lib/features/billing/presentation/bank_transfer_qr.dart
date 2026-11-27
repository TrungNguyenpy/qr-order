import 'package:flutter/material.dart';

import '../../../app/theme.dart';
import '../../../core/util/format.dart';
import '../domain/billing_models.dart';

/// Mã VietQR để khách chuyển khoản. Thu ngân vẫn xác nhận đã nhận tiền bằng tay (không đối soát tự động).
/// [amount] là số tiền đang nhập trong hộp thoại Thu tiền (thu một phần thì QR đổi theo).
/// Ảnh lỗi/chậm thì vẫn hiện thông tin tài khoản để chuyển khoản thủ công.
class BankTransferQr extends StatelessWidget {
  const BankTransferQr({super.key, required this.info, required this.amount});

  final BankTransferInfo info;
  final int amount;

  @override
  Widget build(BuildContext context) {
    final url = info.imageUrlFor(amount);
    return Container(
      key: const ValueKey<String>('bank-transfer-qr'),
      width: double.infinity,
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.border),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          const Text(
            'Chuyển khoản VietQR',
            style: TextStyle(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: AppSpacing.sm),
          Center(
            child: SizedBox(
              width: 240,
              child: Image.network(
                url,
                key: ValueKey<String>(url),
                fit: BoxFit.contain,
                loadingBuilder: (context, child, progress) => progress == null
                    ? child
                    : const SizedBox(
                        height: 240,
                        child: Center(child: CircularProgressIndicator()),
                      ),
                errorBuilder: (context, error, stack) => const Padding(
                  padding: EdgeInsets.symmetric(vertical: AppSpacing.md),
                  child: Text(
                    'Không tải được mã QR. Dùng thông tin bên dưới để chuyển khoản thủ công.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: AppColors.muted),
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: AppSpacing.sm),
          _Line('Số tiền', formatVnd(amount), strong: true),
          _Line('Chủ tài khoản', info.accountName),
          _Line('Số tài khoản', info.accountNo),
          _Line('Nội dung', info.transferNote),
          const SizedBox(height: AppSpacing.xs),
          const Text(
            'Chỉ bật "Đã nhận tiền ngay" khi đã thấy tiền về tài khoản; nếu chưa, để ghi nhận chờ xác nhận.',
            style: TextStyle(color: AppColors.muted, fontSize: 12.5),
          ),
        ],
      ),
    );
  }
}

class _Line extends StatelessWidget {
  const _Line(this.label, this.value, {this.strong = false});

  final String label;
  final String value;
  final bool strong;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 1),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(label, style: const TextStyle(color: AppColors.muted)),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: TextStyle(
                fontWeight: strong ? FontWeight.w700 : FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
