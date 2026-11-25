import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/util/password_policy.dart';
import '../../../shared/widgets/state_views.dart';
import '../application/session_controller.dart';

class ChangePasswordScreen extends ConsumerStatefulWidget {
  const ChangePasswordScreen({super.key});

  @override
  ConsumerState<ChangePasswordScreen> createState() =>
      _ChangePasswordScreenState();
}

class _ChangePasswordScreenState extends ConsumerState<ChangePasswordScreen> {
  final GlobalKey<FormState> _formKey = GlobalKey<FormState>();
  final TextEditingController _current = TextEditingController();
  final TextEditingController _next = TextEditingController();
  final TextEditingController _confirm = TextEditingController();
  bool _obscure = true;
  bool _submitting = false;
  ApiException? _error;

  @override
  void dispose() {
    _current.dispose();
    _next.dispose();
    _confirm.dispose();
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
      // Thành công: backend thu hồi mọi phiên, controller đưa về màn đăng nhập.
      await ref
          .read(sessionControllerProvider.notifier)
          .changePassword(
            currentPassword: _current.text,
            newPassword: _next.text,
          );
    } on ApiException catch (error) {
      if (!mounted) return;
      setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Đổi mật khẩu')),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: <Widget>[
                    const InlineBanner(
                      message: 'Sau khi đổi, mọi thiết bị sẽ bị đăng xuất và cần đăng nhập lại.',
                      foreground: AppColors.info,
                      background: AppColors.infoSoft,
                    ),
                    const SizedBox(height: AppSpacing.lg),
                    if (_error != null) ...<Widget>[
                      InlineBanner(
                        message: _error!.userMessage,
                        icon: Icons.error_outline_rounded,
                        foreground: AppColors.danger,
                        background: AppColors.dangerSoft,
                      ),
                      const SizedBox(height: AppSpacing.md),
                    ],
                    _field(
                      controller: _current,
                      label: 'Mật khẩu hiện tại',
                      errorText: _error?.fieldMessage('currentPassword'),
                      validator: (value) => (value ?? '').isEmpty
                          ? 'Nhập mật khẩu hiện tại'
                          : null,
                    ),
                    const SizedBox(height: AppSpacing.md),
                    _field(
                      controller: _next,
                      label: 'Mật khẩu mới',
                      errorText: _error?.fieldMessage('newPassword'),
                      validator: (value) =>
                          PasswordPolicy.validate(value ?? ''),
                    ),
                    const SizedBox(height: AppSpacing.md),
                    _field(
                      controller: _confirm,
                      label: 'Nhập lại mật khẩu mới',
                      action: TextInputAction.done,
                      validator: (value) => value == _next.text
                          ? null
                          : 'Mật khẩu nhập lại không khớp',
                    ),
                    const SizedBox(height: AppSpacing.sm),
                    const Text(
                      'Tối thiểu 8 ký tự, có chữ hoa, chữ thường và chữ số.',
                      style: TextStyle(color: AppColors.muted, fontSize: 12.5),
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
                          : const Text('Đổi mật khẩu'),
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

  Widget _field({
    required TextEditingController controller,
    required String label,
    required String? Function(String?) validator,
    String? errorText,
    TextInputAction action = TextInputAction.next,
  }) {
    return TextFormField(
      controller: controller,
      enabled: !_submitting,
      obscureText: _obscure,
      textInputAction: action,
      onFieldSubmitted: action == TextInputAction.done
          ? (_) => _submit()
          : null,
      decoration: InputDecoration(
        labelText: label,
        errorText: errorText,
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
      validator: validator,
    );
  }
}
