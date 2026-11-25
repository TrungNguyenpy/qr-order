import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/config/server_url.dart';
import '../../../core/network/api_client.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/providers/core_providers.dart';
import '../../../core/storage/token_store.dart';
import '../../../shared/widgets/state_views.dart';
import '../data/auth_repository.dart';

/// Hộp thoại đổi địa chỉ máy chủ (chỉ ở màn đăng nhập), kèm "Kiểm tra kết nối".
Future<void> showServerSettingsSheet(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    constraints: const BoxConstraints(maxWidth: 520),
    builder: (_) => const _ServerSettingsSheet(),
  );
}

enum _TestState { idle, testing, ok, failed }

class _ServerSettingsSheet extends ConsumerStatefulWidget {
  const _ServerSettingsSheet();

  @override
  ConsumerState<_ServerSettingsSheet> createState() =>
      _ServerSettingsSheetState();
}

class _ServerSettingsSheetState extends ConsumerState<_ServerSettingsSheet> {
  late final TextEditingController _controller = TextEditingController(
    text: ref.read(serverUrlProvider),
  );
  String? _fieldError;
  String? _testMessage;
  _TestState _test = _TestState.idle;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  String? _normalize() {
    try {
      final normalized = normalizeServerUrl(_controller.text);
      setState(() => _fieldError = null);
      return normalized;
    } on ServerUrlException catch (error) {
      setState(() => _fieldError = error.message);
      return null;
    }
  }

  Future<void> _testConnection() async {
    final url = _normalize();
    if (url == null) return;
    setState(() {
      _test = _TestState.testing;
      _testMessage = null;
    });
    final client = ApiClient(
      baseUrl: url,
      tokenStore: InMemoryTokenStore(),
      onSessionExpired: (_) {},
    );
    try {
      await AuthRepository(client, InMemoryTokenStore()).ping();
      if (!mounted) return;
      setState(() {
        _test = _TestState.ok;
        _testMessage = 'Kết nối máy chủ thành công.';
      });
    } on ApiException catch (error) {
      if (!mounted) return;
      setState(() {
        _test = _TestState.failed;
        _testMessage = error.userMessage;
      });
    } finally {
      client.close();
    }
  }

  Future<void> _save() async {
    final url = _normalize();
    if (url == null) return;
    await ref.read(serverUrlProvider.notifier).update(url);
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.viewInsetsOf(context).bottom;
    return Padding(
      padding: EdgeInsets.fromLTRB(
        AppSpacing.lg,
        0,
        AppSpacing.lg,
        bottom + AppSpacing.lg,
      ),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            const Text(
              'Địa chỉ máy chủ',
              style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: AppSpacing.xs),
            const Text(
              'Emulator Android dùng 10.0.2.2. Thiết bị thật dùng IP LAN của máy chủ, ví dụ 192.168.1.20:8080.',
              style: TextStyle(color: AppColors.muted, fontSize: 13),
            ),
            const SizedBox(height: AppSpacing.md),
            TextField(
              controller: _controller,
              keyboardType: TextInputType.url,
              autocorrect: false,
              enableSuggestions: false,
              decoration: InputDecoration(
                labelText: 'URL máy chủ',
                hintText: 'http://10.0.2.2:8080',
                errorText: _fieldError,
              ),
              onChanged: (_) {
                if (_test != _TestState.idle) {
                  setState(() => _test = _TestState.idle);
                }
              },
            ),
            if (_testMessage != null) ...<Widget>[
              const SizedBox(height: AppSpacing.sm),
              InlineBanner(
                message: _testMessage!,
                icon: _test == _TestState.ok
                    ? Icons.check_circle_outline_rounded
                    : Icons.error_outline_rounded,
                foreground: _test == _TestState.ok
                    ? AppColors.success
                    : AppColors.danger,
                background: _test == _TestState.ok
                    ? AppColors.successSoft
                    : AppColors.dangerSoft,
              ),
            ],
            const SizedBox(height: AppSpacing.lg),
            Row(
              children: <Widget>[
                Expanded(
                  child: OutlinedButton(
                    onPressed: _test == _TestState.testing
                        ? null
                        : _testConnection,
                    child: _test == _TestState.testing
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text('Kiểm tra kết nối'),
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: FilledButton(
                    onPressed: _save,
                    child: const Text('Lưu'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
