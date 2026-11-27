import 'package:flutter/material.dart';

import '../../../app/theme.dart';
import '../../../core/util/format.dart';
import '../../../shared/widgets/app_card.dart';
import '../domain/write_off.dart';

/// Thẻ "Ghi nhận thất thu" (`WRITTEN_OFF`): hiện rõ số tiền thất thu và các số
/// liệu liên quan. Dùng ở chi tiết bàn, thu ngân và kết quả đóng cưỡng bức phiên.
class WriteOffCard extends StatelessWidget {
  const WriteOffCard({super.key, required this.info});

  final WriteOffInfo info;

  @override
  Widget build(BuildContext context) {
    final shortfall = info.shortfallAmount;
    return AppCard(
      color: AppColors.dangerSoft,
      borderColor: AppColors.danger,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          const Row(
            children: <Widget>[
              Icon(
                Icons.report_gmailerrorred_rounded,
                size: 20,
                color: AppColors.danger,
              ),
              SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(
                  'Đã ghi nhận thất thu',
                  style: TextStyle(
                    color: AppColors.danger,
                    fontWeight: FontWeight.w700,
                    fontSize: 15,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          _Line(
            'Số tiền thất thu',
            shortfall == null ? 'Chưa có số liệu' : formatVnd(shortfall),
            strong: true,
          ),
          if (info.totalAmount != null)
            _Line('Tổng hóa đơn', formatVnd(info.totalAmount!)),
          if (info.collectedAmount != null)
            _Line('Đã thu', formatVnd(info.collectedAmount!)),
          if (info.billNumber != null) _Line('Hóa đơn', info.billNumber!),
          if ((info.reason ?? '').isNotEmpty) _Line('Lý do', info.reason!),
          if (info.writtenOffAt != null)
            _Line('Ghi nhận lúc', formatDayClock(info.writtenOffAt!)),
          const SizedBox(height: AppSpacing.sm),
          const Text(
            'Thất thu không tính vào doanh thu. Đây là trạng thái cuối: không thu '
            'thêm, không hoàn tiền, không sửa hóa đơn.',
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
      padding: const EdgeInsets.symmetric(vertical: 2),
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
                fontSize: strong ? 16 : 14,
                color: strong ? AppColors.danger : AppColors.text,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
