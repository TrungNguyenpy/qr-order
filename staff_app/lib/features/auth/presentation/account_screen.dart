import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../../core/config/app_config.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/providers/core_providers.dart';
import '../../../core/util/format.dart';
import '../../../shared/widgets/app_card.dart';
import '../application/session_controller.dart';
import '../domain/auth_models.dart';

class AccountScreen extends ConsumerWidget {
  const AccountScreen({super.key});

  Future<void> _refresh(BuildContext context, WidgetRef ref) async {
    try {
      await ref.read(sessionControllerProvider.notifier).refreshUser();
    } on ApiException catch (error) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(error.userMessage)));
    }
  }

  Future<void> _confirmLogout(BuildContext context, WidgetRef ref) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Đăng xuất?'),
        content: const Text('Bạn sẽ cần đăng nhập lại để tiếp tục.'),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Hủy'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Đăng xuất'),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      await ref.read(sessionControllerProvider.notifier).logout();
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(sessionControllerProvider).user;
    final serverUrl = ref.watch(serverUrlProvider);
    if (user == null) return const SizedBox.shrink();

    return Scaffold(
      appBar: AppBar(title: const Text('Tài khoản')),
      body: RefreshIndicator(
        onRefresh: () => _refresh(context, ref),
        child: ListView(
          padding: const EdgeInsets.all(AppSpacing.lg),
          children: <Widget>[
            Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 560),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: <Widget>[
                    AppCard(
                      child: Row(
                        children: <Widget>[
                          CircleAvatar(
                            radius: 24,
                            backgroundColor: AppColors.primarySoft,
                            child: Text(
                              user.displayName.isEmpty
                                  ? '?'
                                  : String.fromCharCode(
                                      user.displayName.runes.first,
                                    ).toUpperCase(),
                              style: const TextStyle(
                                color: AppColors.primary,
                                fontSize: 20,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                          const SizedBox(width: AppSpacing.md),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: <Widget>[
                                Text(
                                  user.displayName,
                                  style: const TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                Text(
                                  '@${user.username}',
                                  style: const TextStyle(
                                    color: AppColors.muted,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          StatusPill(
                            label: user.role.label,
                            foreground: AppColors.primary,
                            background: AppColors.primarySoft,
                          ),
                        ],
                      ),
                    ),
                    if (user.lastLoginAt != null) ...<Widget>[
                      const SizedBox(height: AppSpacing.sm),
                      Text(
                        'Đăng nhập gần nhất: ${formatDayClock(user.lastLoginAt!)}',
                        style: const TextStyle(
                          color: AppColors.muted,
                          fontSize: 12.5,
                        ),
                      ),
                    ],
                    const SizedBox(height: AppSpacing.lg),
                    const _SectionTitle('Quyền nghiệp vụ'),
                    AppCard(
                      padding: EdgeInsets.zero,
                      child: Column(
                        children: <Widget>[
                          for (
                            var i = 0;
                            i < Permission.values.length;
                            i++
                          ) ...<Widget>[
                            if (i > 0) const Divider(height: 1),
                            _PermissionRow(
                              permission: Permission.values[i],
                              granted:
                                  user.can(Permission.values[i]) ||
                                  user.role == UserRole.manager,
                            ),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(height: AppSpacing.lg),
                    const _SectionTitle('Ứng dụng'),
                    AppCard(
                      padding: EdgeInsets.zero,
                      child: Column(
                        children: <Widget>[
                          ListTile(
                            dense: true,
                            leading: const Icon(Icons.dns_outlined),
                            title: const Text('Máy chủ'),
                            subtitle: Text(serverUrl),
                          ),
                          const Divider(height: 1),
                          const ListTile(
                            dense: true,
                            leading: Icon(Icons.info_outline_rounded),
                            title: Text('Phiên bản'),
                            subtitle: Text(AppConfig.appVersionLabel),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: AppSpacing.xl),
                    OutlinedButton.icon(
                      onPressed: () => context.push('/change-password'),
                      icon: const Icon(Icons.lock_reset_rounded, size: 18),
                      label: const Text('Đổi mật khẩu'),
                    ),
                    const SizedBox(height: AppSpacing.sm),
                    OutlinedButton.icon(
                      onPressed: () => _confirmLogout(context, ref),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: AppColors.danger,
                      ),
                      icon: const Icon(Icons.logout_rounded, size: 18),
                      label: const Text('Đăng xuất'),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: Text(
        text,
        style: const TextStyle(
          color: AppColors.muted,
          fontSize: 12.5,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _PermissionRow extends StatelessWidget {
  const _PermissionRow({required this.permission, required this.granted});

  final Permission permission;
  final bool granted;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      child: Row(
        children: <Widget>[
          Icon(
            granted ? Icons.check_circle_rounded : Icons.lock_outline_rounded,
            size: 18,
            color: granted ? AppColors.success : AppColors.muted,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              permission.label,
              style: TextStyle(
                color: granted ? AppColors.text : AppColors.muted,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
