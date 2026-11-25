import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/providers/core_providers.dart';
import '../../../shared/widgets/state_views.dart';
import '../application/session_controller.dart';
import 'server_settings_sheet.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final GlobalKey<FormState> _formKey = GlobalKey<FormState>();
  final TextEditingController _username = TextEditingController();
  final TextEditingController _password = TextEditingController();
  bool _obscure = true;
  bool _submitting = false;
  ApiException? _error;

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    FocusScope.of(context).unfocus();
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      await ref
          .read(sessionControllerProvider.notifier)
          .login(_username.text, _password.text);
      // Router tự chuyển màn hình khi trạng thái đăng nhập đổi.
    } on ApiException catch (error) {
      if (!mounted) return;
      setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final notice = ref.watch(sessionControllerProvider).notice;
    final serverUrl = ref.watch(serverUrlProvider);

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(AppSpacing.xl),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 400),
              child: Form(
                key: _formKey,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: <Widget>[
                    const Icon(
                      Icons.qr_code_2_rounded,
                      size: 44,
                      color: AppColors.primary,
                    ),
                    const SizedBox(height: AppSpacing.md),
                    const Text(
                      'QR Order · Nhân viên',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: AppSpacing.xs),
                    const Text(
                      'Đăng nhập để nhận và xử lý đơn tại bàn',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: AppColors.muted),
                    ),
                    const SizedBox(height: AppSpacing.xl),
                    if (notice != null && _error == null) ...<Widget>[
                      InlineBanner(message: notice),
                      const SizedBox(height: AppSpacing.md),
                    ],
                    if (_error != null) ...<Widget>[
                      InlineBanner(
                        message: _error!.userMessage,
                        icon: Icons.error_outline_rounded,
                        foreground: AppColors.danger,
                        background: AppColors.dangerSoft,
                      ),
                      const SizedBox(height: AppSpacing.md),
                    ],
                    TextFormField(
                      controller: _username,
                      enabled: !_submitting,
                      textInputAction: TextInputAction.next,
                      autocorrect: false,
                      enableSuggestions: false,
                      autofillHints: const <String>[AutofillHints.username],
                      decoration: InputDecoration(
                        labelText: 'Tên đăng nhập',
                        errorText: _error?.fieldMessage('username'),
                      ),
                      validator: (value) => (value ?? '').trim().isEmpty
                          ? 'Nhập tên đăng nhập'
                          : null,
                    ),
                    const SizedBox(height: AppSpacing.md),
                    TextFormField(
                      controller: _password,
                      enabled: !_submitting,
                      obscureText: _obscure,
                      textInputAction: TextInputAction.done,
                      autofillHints: const <String>[AutofillHints.password],
                      onFieldSubmitted: (_) => _submit(),
                      decoration: InputDecoration(
                        labelText: 'Mật khẩu',
                        errorText: _error?.fieldMessage('password'),
                        suffixIcon: IconButton(
                          tooltip: _obscure ? 'Hiện mật khẩu' : 'Ẩn mật khẩu',
                          onPressed: () => setState(() => _obscure = !_obscure),
                          icon: Icon(
                            _obscure
                                ? Icons.visibility_outlined
                                : Icons.visibility_off_outlined,
                          ),
                        ),
                      ),
                      validator: (value) =>
                          (value ?? '').isEmpty ? 'Nhập mật khẩu' : null,
                    ),
                    const SizedBox(height: AppSpacing.xl),
                    FilledButton(
                      onPressed: _submitting ? null : _submit,
                      child: _submitting
                          ? const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: Colors.white,
                              ),
                            )
                          : const Text('Đăng nhập'),
                    ),
                    const SizedBox(height: AppSpacing.lg),
                    Row(
                      children: <Widget>[
                        const Icon(
                          Icons.dns_outlined,
                          size: 16,
                          color: AppColors.muted,
                        ),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            serverUrl,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              color: AppColors.muted,
                              fontSize: 12.5,
                            ),
                          ),
                        ),
                        TextButton(
                          onPressed: _submitting
                              ? null
                              : () => showServerSettingsSheet(context),
                          child: const Text('Đổi máy chủ'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
