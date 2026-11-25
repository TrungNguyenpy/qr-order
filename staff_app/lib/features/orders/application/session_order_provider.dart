import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/providers/core_providers.dart';
import '../domain/order_models.dart';

/// Order hiện tại của một phiên (xem chi tiết bàn, chỉ đọc ở Đợt 1).
final sessionOrderProvider = FutureProvider.autoDispose
    .family<SessionOrder, int>((ref, sessionId) {
      return ref.watch(ordersRepositoryProvider).sessionOrder(sessionId);
    });
