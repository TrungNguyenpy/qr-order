import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/providers/live_list.dart';
import '../../../core/realtime/realtime_message.dart';
import '../domain/order_action_models.dart';
import '../domain/order_models.dart';

/// Đợt chờ xác nhận (`/topic/staff/orders`).
class PendingBatchesController extends LiveListController<OrderBatch> {
  @override
  Set<String> get topics => const <String>{StompTopics.staffOrders};

  @override
  Future<List<OrderBatch>> fetch() =>
      ref.read(ordersRepositoryProvider).pendingBatches();
}

final pendingBatchesProvider =
    AsyncNotifierProvider<PendingBatchesController, List<OrderBatch>>(
      PendingBatchesController.new,
    );

/// Món READY chờ mang ra bàn (`/topic/staff/ready`).
class ReadyItemsController extends LiveListController<KitchenItem> {
  @override
  Set<String> get topics => const <String>{
    StompTopics.staffReady,
    StompTopics.staffOrders,
  };

  @override
  Future<List<KitchenItem>> fetch() =>
      ref.read(ordersRepositoryProvider).readyItems();
}

final readyItemsProvider =
    AsyncNotifierProvider<ReadyItemsController, List<KitchenItem>>(
      ReadyItemsController.new,
    );

/// Yêu cầu hủy món chờ duyệt (cần `ITEM_CANCEL_APPROVE`).
class CancelRequestsController extends LiveListController<CancelRequest> {
  @override
  Set<String> get topics => const <String>{StompTopics.staffOrders};

  @override
  Future<List<CancelRequest>> fetch() =>
      ref.read(ordersRepositoryProvider).cancelRequests();
}

final cancelRequestsProvider =
    AsyncNotifierProvider<CancelRequestsController, List<CancelRequest>>(
      CancelRequestsController.new,
    );
