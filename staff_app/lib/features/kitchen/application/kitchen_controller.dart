import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/providers/live_list.dart';
import '../../../core/realtime/realtime_message.dart';
import '../../orders/domain/order_action_models.dart';

/// Món chờ nấu / đang nấu cho bếp (`/topic/kitchen/orders`), cũ trước.
class KitchenController extends LiveListController<KitchenItem> {
  @override
  Set<String> get topics => const <String>{StompTopics.kitchenOrders};

  @override
  Future<List<KitchenItem>> fetch() =>
      ref.read(ordersRepositoryProvider).kitchenItems();
}

final kitchenItemsProvider =
    AsyncNotifierProvider<KitchenController, List<KitchenItem>>(
      KitchenController.new,
    );
