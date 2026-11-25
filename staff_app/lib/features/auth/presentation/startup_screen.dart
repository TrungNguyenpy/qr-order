import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../shared/widgets/state_views.dart';
import '../application/session_controller.dart';

/// Màn hình chờ khi khôi phục phiên; nếu mất mạng thì cho thử lại hoặc đăng xuất.
class StartupScreen extends ConsumerWidget {
  const StartupScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(sessionControllerProvider);
    final controller = ref.read(sessionControllerProvider.notifier);

    if (auth.status == AuthStatus.restoreFailed) {
      return Scaffold(
        body: SafeArea(
          child: ErrorView(
            title: 'Không khôi phục được phiên đăng nhập',
            error: auth.error ?? Exception('unknown'),
            onRetry: controller.restore,
          ),
        ),
        bottomNavigationBar: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: TextButton(
              onPressed: controller.logout,
              child: const Text('Đăng xuất và dùng tài khoản khác'),
            ),
          ),
        ),
      );
    }

    return const Scaffold(
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            SizedBox(
              width: 28,
              height: 28,
              child: CircularProgressIndicator(strokeWidth: 2.5),
            ),
            SizedBox(height: AppSpacing.md),
            Text(
              'Đang khôi phục phiên đăng nhập...',
              style: TextStyle(color: AppColors.muted),
            ),
          ],
        ),
      ),
    );
  }
}
