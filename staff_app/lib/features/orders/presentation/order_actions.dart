import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/providers/core_providers.dart';
import '../../../shared/reason_dialog.dart';
import '../../../shared/ui_actions.dart';
import '../../auth/application/session_controller.dart';
import '../../auth/domain/auth_models.dart';
import '../domain/order_models.dart';
import 'place_order_sheet.dart';

/// Nút thao tác của phiên trong chi tiết bàn: gọi món hộ khách, gọi Manager.
class OrderActions extends ConsumerWidget {
  const OrderActions({
    super.key,
    required this.sessionId,
    required this.tableCode,
    required this.orderStatus,
    required this.loaded,
  });

  final int sessionId;
  final String tableCode;
  final OrderStatus? orderStatus;

  /// Đã tải xong order của phiên (chưa tải thì chưa biết có gọi hộ được không).
  final bool loaded;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(sessionControllerProvider).user;
    if (user == null) return const SizedBox.shrink();
    final canOrder = loaded && canPlaceOrderIn(orderStatus);
    final canAskManager = user.role == UserRole.staff;
    if (!canOrder && !canAskManager) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: Wrap(
        spacing: AppSpacing.sm,
        runSpacing: AppSpacing.sm,
        children: <Widget>[
          if (canOrder)
            FilledButton.icon(
              onPressed: () => showPlaceOrderSheet(
                context,
                sessionId: sessionId,
                tableCode: tableCode,
              ),
              icon: const Icon(Icons.add_rounded, size: 18),
              label: const Text('Gọi món hộ'),
            ),
          if (canAskManager)
            OutlinedButton.icon(
              onPressed: () async {
                final note = await showOptionalTextDialog(
                  context,
                  title: 'Gọi Manager hỗ trợ bàn $tableCode',
                  label: 'Cần hỗ trợ gì? (tùy chọn)',
                  confirmLabel: 'Gửi',
                );
                if (note == null || !context.mounted) return;
                await runGuarded(
                  context,
                  () => ref
                      .read(serviceCallRepositoryProvider)
                      .requestManager(sessionId, note: note),
                  success: 'Đã gửi yêu cầu cho Manager.',
                );
              },
              icon: const Icon(Icons.support_agent_rounded, size: 18),
              label: const Text('Gọi Manager'),
            ),
        ],
      ),
    );
  }
}
