import '../config/app_config.dart';

/// Đường dẫn REST của backend (khớp controller thật, xem README dự án).
class ApiPaths {
  const ApiPaths._();

  static const String _p = AppConfig.apiPrefix;

  static const String login = '$_p/auth/login';
  static const String refresh = '$_p/auth/refresh';
  static const String logout = '$_p/auth/logout';
  static const String me = '$_p/auth/me';
  static const String changePassword = '$_p/auth/change-password';

  static const String floorPlan = '$_p/tables/floor-plan';
  static String tableStatus(int tableId) => '$_p/tables/$tableId/status';
  static String session(int sessionId) => '$_p/sessions/$sessionId';
  static String sessionOrder(int sessionId) => '$_p/orders/sessions/$sessionId';

  // --- Đơn hàng (STAFF, MANAGER) ---
  static const String pendingBatches = '$_p/orders/batches/pending';
  static String batchAction(int batchId, String action) =>
      '$_p/orders/batches/$batchId/$action';
  static const String readyItems = '$_p/orders/items/ready';
  static String serveItem(int itemId) => '$_p/orders/items/$itemId/serve';
  static const String cancelRequests = '$_p/orders/cancel-requests';
  static String cancelRequestAction(int id, String action) =>
      '$_p/orders/cancel-requests/$id/$action';

  // --- Bếp (KITCHEN, MANAGER) ---
  static const String kitchenItems = '$_p/kitchen/items';
  static String kitchenItemStatus(int itemId) =>
      '$_p/kitchen/items/$itemId/status';
  static String kitchenItemReject(int itemId) =>
      '$_p/kitchen/items/$itemId/reject';

  // --- Thu ngân ---
  static String orderBill(int orderId) => '$_p/orders/$orderId/bill';
  static String billPayments(int billId) => '$_p/bills/$billId/payments';
  static String paymentConfirm(int paymentId) =>
      '$_p/payments/$paymentId/confirm';
  static String paymentReceipt(int paymentId) =>
      '$_p/payments/$paymentId/receipt';

  // --- Đợt 3: yêu cầu hỗ trợ, gọi món hộ, sửa món, menu ---
  static const String serviceCalls = '$_p/service-calls';
  static String serviceCallAction(int id, String action) =>
      '$_p/service-calls/$id/$action';
  static String sessionBatches(int sessionId) =>
      '$_p/orders/sessions/$sessionId/batches';
  static String itemQuantity(int itemId) => '$_p/orders/items/$itemId/quantity';
  static String itemCancel(int itemId) => '$_p/orders/items/$itemId/cancel';
  static const String categories = '$_p/categories';
  static const String products = '$_p/products';
  static String product(int id) => '$_p/products/$id';
  static String optionGroup(int id) => '$_p/option-groups/$id';

  // --- Đợt 3: hóa đơn của Manager ---
  static String billVoid(int billId) => '$_p/bills/$billId/void';
  static String billVoidWithdraw(int billId) =>
      '$_p/bills/$billId/void/withdraw';
  static String replacementBill(int orderId) =>
      '$_p/orders/$orderId/replacement-bill';
  static const String billingAttention = '$_p/billing/attention';
  static String billRefunds(int billId) => '$_p/bills/$billId/refunds';
  static String refundConfirm(int refundId) => '$_p/refunds/$refundId/confirm';
  static String refundReceipt(int refundId) => '$_p/refunds/$refundId/receipt';
  static String sessionReopen(int sessionId) =>
      '$_p/sessions/$sessionId/reopen';

  // --- Đợt 4: đóng cưỡng bức phiên, danh sách phiên ---
  static const String sessions = '$_p/sessions';
  static String sessionForceClose(int sessionId) =>
      '$_p/sessions/$sessionId/force-close';
  static String orderBills(int orderId) => '$_p/orders/$orderId/bills';

  /// Kiểm tra máy chủ sống (Spring Actuator, công khai).
  static const String health = '/actuator/health';
}
