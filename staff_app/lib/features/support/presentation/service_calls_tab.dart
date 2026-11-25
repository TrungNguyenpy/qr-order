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
import '../../../shared/widgets/live_list_view.dart';
import '../../auth/application/session_controller.dart';
import '../../auth/domain/auth_models.dart';
import '../application/service_call_providers.dart';
import '../domain/service_call_models.dart';

/// Tab "Hỗ trợ": khách gọi nhân viên, xin thanh toán, nhân viên cần Manager.
class ServiceCallsTab extends ConsumerWidget {
  const ServiceCallsTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return LiveListView<ServiceCall>(
      value: ref.watch(serviceCallsProvider),
      onRefresh: () async {
        final messenger = ScaffoldMessenger.of(context);
        try {
          await ref.read(serviceCallsProvider.notifier).reload();
        } on ApiException catch (error) {
          messenger.showSnackBar(SnackBar(content: Text(error.userMessage)));
        }
      },
      onRetry: () => ref.invalidate(serviceCallsProvider),
      emptyIcon: Icons.notifications_none_rounded,
      emptyTitle: 'Không có yêu cầu hỗ trợ',
      emptyMessage:
          'Khách bấm gọi nhân viên hoặc xin thanh toán sẽ hiện ở đây ngay.',
      itemBuilder: (context, call) => ServiceCallCard(call: call),
    );
  }
}

/// Thẻ một yêu cầu: nhận xử lý rồi hoàn tất. MANAGER_SUPPORT chỉ Manager thao tác.
class ServiceCallCard extends ConsumerWidget {
  const ServiceCallCard({super.key, required this.call});

  final ServiceCall call;

  Future<void> _reload(WidgetRef ref) async {
    try {
      await ref.read(serviceCallsProvider.notifier).reload();
    } on ApiException {
      // Lỗi đã được báo ở thao tác chính.
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(sessionControllerProvider).user;
    final isManager = user?.role == UserRole.manager;
    final canHandle = call.canHandle(isManager: isManager);
    final isOpen = call.status == ServiceCallStatus.open;
    final createdAt = call.createdAt;
    final urgent = call.type == ServiceCallType.requestPayment;
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              Text(
                call.tableCode ?? 'Bàn ?',
                style: const TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              StatusPill(
                label: call.type.label,
                foreground: urgent ? AppColors.warning : AppColors.info,
                background: urgent ? AppColors.warningSoft : AppColors.infoSoft,
              ),
              const Spacer(),
              if (createdAt != null)
                Text(
                  formatElapsed(createdAt, DateTime.now()),
                  style: const TextStyle(
                    color: AppColors.muted,
                    fontSize: 12.5,
                  ),
                ),
            ],
          ),
          if ((call.note ?? '').isNotEmpty) ...<Widget>[
            const SizedBox(height: AppSpacing.xs),
            Text(call.note!, style: const TextStyle(color: AppColors.muted)),
          ],
          const SizedBox(height: AppSpacing.xs),
          Text(
            call.status.label,
            style: const TextStyle(color: AppColors.muted, fontSize: 12.5),
          ),
          const SizedBox(height: AppSpacing.md),
          if (!canHandle)
            const Text(
              'Chỉ Manager xử lý yêu cầu này.',
              style: TextStyle(color: AppColors.muted, fontSize: 12.5),
            )
          else
            Row(
              children: <Widget>[
                if (isOpen)
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () async {
                        await runGuarded(
                          context,
                          () => ref
                              .read(serviceCallRepositoryProvider)
                              .acknowledge(call.id),
                          success: 'Đã nhận xử lý.',
                          onError: (_) => _reload(ref),
                        );
                        unawaited(_reload(ref));
                      },
                      child: const Text('Nhận xử lý'),
                    ),
                  ),
                if (isOpen) const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: FilledButton(
                    onPressed: () async {
                      final note = await showOptionalTextDialog(
                        context,
                        title: 'Hoàn tất yêu cầu',
                        label: 'Ghi chú (tùy chọn)',
                        confirmLabel: 'Hoàn tất',
                      );
                      if (note == null || !context.mounted) return;
                      await runGuarded(
                        context,
                        () => ref
                            .read(serviceCallRepositoryProvider)
                            .resolve(call.id, note: note),
                        success: 'Đã hoàn tất yêu cầu.',
                        onError: (_) => _reload(ref),
                      );
                      unawaited(_reload(ref));
                    },
                    child: const Text('Hoàn tất'),
                  ),
                ),
              ],
            ),
        ],
      ),
    );
  }
}
