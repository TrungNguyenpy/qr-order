import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/providers/live_list.dart';
import '../../../core/realtime/realtime_message.dart';
import '../domain/service_call_models.dart';

/// Hàng đợi yêu cầu hỗ trợ; backend đẩy tín hiệu qua `/topic/staff/orders`.
class ServiceCallsController extends LiveListController<ServiceCall> {
  @override
  Set<String> get topics => const <String>{StompTopics.staffOrders};

  @override
  Future<List<ServiceCall>> fetch() =>
      ref.read(serviceCallRepositoryProvider).queue();
}

final serviceCallsProvider =
    AsyncNotifierProvider<ServiceCallsController, List<ServiceCall>>(
      ServiceCallsController.new,
    );
