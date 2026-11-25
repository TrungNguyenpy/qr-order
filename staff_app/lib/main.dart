import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app/app.dart';
import 'core/config/app_config.dart';
import 'core/providers/core_providers.dart';
import 'core/storage/prefs_server_url_store.dart';
import 'features/auth/application/session_controller.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();

  final container = ProviderContainer(
    overrides: [
      serverUrlStoreProvider.overrideWithValue(
        PrefsServerUrlStore(prefs, defaultUrl: AppConfig.defaultApiBaseUrl),
      ),
    ],
  );

  // Khôi phục phiên (không chặn khung hình đầu: StartupScreen hiện trong lúc chờ).
  unawaited(container.read(sessionControllerProvider.notifier).restore());

  runApp(
    UncontrolledProviderScope(container: container, child: const StaffApp()),
  );
}
