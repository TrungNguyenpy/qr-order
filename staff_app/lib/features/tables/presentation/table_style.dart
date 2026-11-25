import 'package:flutter/material.dart';

import '../../../app/theme.dart';
import '../domain/table_models.dart';

/// Màu theo trạng thái bàn.
class TableStatusStyle {
  const TableStatusStyle(this.foreground, this.background);

  final Color foreground;
  final Color background;
}

TableStatusStyle styleForTableStatus(TableStatus status) {
  switch (status) {
    case TableStatus.available:
      return const TableStatusStyle(AppColors.success, AppColors.successSoft);
    case TableStatus.occupied:
      return const TableStatusStyle(AppColors.info, AppColors.infoSoft);
    case TableStatus.paymentPending:
      return const TableStatusStyle(AppColors.warning, AppColors.warningSoft);
    case TableStatus.cleaning:
      return const TableStatusStyle(AppColors.muted, AppColors.neutralSoft);
  }
}
