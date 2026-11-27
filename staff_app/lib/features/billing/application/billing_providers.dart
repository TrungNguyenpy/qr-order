import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/providers/core_providers.dart';
import '../../../core/providers/live_list.dart';
import '../../../core/realtime/realtime_message.dart';
import '../domain/billing_models.dart';

/// Bill hiện hành của một Order; null khi chưa tính tiền.
final billProvider = FutureProvider.autoDispose.family<Bill?, int>((
  ref,
  orderId,
) {
  return ref.watch(billingRepositoryProvider).latestBill(orderId);
});

/// Phiếu thanh toán của một giao dịch SUCCESS.
final receiptProvider = FutureProvider.autoDispose.family<PaymentReceipt, int>((
  ref,
  paymentId,
) {
  return ref.watch(billingRepositoryProvider).receipt(paymentId);
});

/// Biên lai hoàn tiền của một giao dịch hoàn SUCCESS.
final refundReceiptProvider = FutureProvider.autoDispose
    .family<RefundReceipt, int>((ref, refundId) {
      return ref.watch(billingRepositoryProvider).refundReceipt(refundId);
    });

/// Các giao dịch hoàn tiền của một hóa đơn (mới nhất trước).
final billRefundsProvider = FutureProvider.autoDispose
    .family<List<Refund>, int>((ref, billId) async {
      final list = await ref.watch(billingRepositoryProvider).refunds(billId);
      return List<Refund>.of(list)..sort((a, b) => b.id.compareTo(a.id));
    });

/// Mục "Cần xử lý" của hóa đơn (chờ hoàn tiền, chờ hóa đơn thay thế).
class AttentionController extends LiveListController<AttentionItem> {
  @override
  Set<String> get topics => const <String>{
    StompTopics.tables,
    StompTopics.staffOrders,
  };

  @override
  Future<List<AttentionItem>> fetch() =>
      ref.read(billingRepositoryProvider).attention();
}

final attentionProvider =
    AsyncNotifierProvider<AttentionController, List<AttentionItem>>(
      AttentionController.new,
    );
