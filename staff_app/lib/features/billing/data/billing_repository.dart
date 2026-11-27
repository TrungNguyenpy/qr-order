import '../../../core/network/api_client.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/network/api_paths.dart';
import '../../../core/util/json_utils.dart';
import '../domain/billing_models.dart';

/// Gọi API thu ngân. Mọi lỗi nghiệp vụ (BATCH_PENDING, NO_BILLABLE_ITEMS,
/// PAYMENT_EXCEEDS_REMAINING, ...) được ném dưới dạng [ApiException] có thông điệp tiếng Việt.
class BillingRepository {
  BillingRepository(this._client);

  final ApiClient _client;

  T _parse<T>(T Function() builder) {
    try {
      return builder();
    } on FormatException {
      throw ApiException.unexpected();
    }
  }

  /// Bill hiện hành (hoặc gần nhất) của Order. 404 khi chưa tính tiền: trả null.
  Future<Bill?> latestBill(int orderId) async {
    try {
      final data = await _client.get(ApiPaths.orderBill(orderId));
      return _parse(() => Bill.fromJson(asMap(data)));
    } on ApiException catch (error) {
      if (error.statusCode == 404) return null;
      rethrow;
    }
  }

  /// Tính tiền: tạo Bill, khóa Order, phiên BILLING, bàn PAYMENT_PENDING.
  /// [discount] cần thêm quyền `BILL_DISCOUNT`.
  Future<Bill> createBill(int orderId, {DiscountInput? discount}) async {
    final data = await _client.post(
      ApiPaths.orderBill(orderId),
      data: discount == null
          ? <String, dynamic>{}
          : <String, dynamic>{'discount': discount.toJson()},
    );
    return _parse(() => Bill.fromJson(asMap(data)));
  }

  /// Ghi nhận thu tiền. [requestId] là khóa idempotency: gửi lại cùng giá trị
  /// khi thử lại sau lỗi mạng để không thu trùng.
  Future<PaymentResult> recordPayment(
    int billId, {
    required String requestId,
    required PaymentMethod method,
    required int amount,
    required bool confirmed,
    String? note,
    String? provider,
    String? providerTxnId,
  }) async {
    final data = await _client.post(
      ApiPaths.billPayments(billId),
      data: <String, dynamic>{
        'requestId': requestId,
        'method': method.apiName,
        'amount': amount,
        'status': confirmed ? 'SUCCESS' : 'PENDING',
        if (provider != null && provider.trim().isNotEmpty)
          'provider': provider.trim(),
        if (providerTxnId != null && providerTxnId.trim().isNotEmpty)
          'providerTxnId': providerTxnId.trim(),
        if (note != null && note.trim().isNotEmpty) 'note': note.trim(),
      },
    );
    return _parse(() => PaymentResult.fromJson(asMap(data)));
  }

  /// Xác nhận giao dịch PENDING → SUCCESS, hoặc FAILED kèm lý do.
  Future<PaymentResult> confirmPayment(
    int paymentId, {
    required bool success,
    String? failureReason,
  }) async {
    final data = await _client.post(
      ApiPaths.paymentConfirm(paymentId),
      data: <String, dynamic>{
        'result': success ? 'SUCCESS' : 'FAILED',
        if (!success) 'failureReason': (failureReason ?? '').trim(),
      },
    );
    return _parse(() => PaymentResult.fromJson(asMap(data)));
  }

  /// Phiếu thanh toán nội bộ (chỉ giao dịch SUCCESS).
  Future<PaymentReceipt> receipt(int paymentId) async {
    final data = await _client.get(ApiPaths.paymentReceipt(paymentId));
    return _parse(() => PaymentReceipt.fromJson(asMap(data)));
  }

  // ---------------------------------------------------------------- Manager

  /// Yêu cầu Void (`BILL_VOID`): chưa thu tiền → VOIDED ngay; đã thu → VOID_PENDING chờ hoàn.
  Future<Bill> requestVoid(int billId, {required String reason}) async {
    final data = await _client.post(
      ApiPaths.billVoid(billId),
      data: <String, dynamic>{'reason': reason.trim()},
    );
    return _parse(() => Bill.fromJson(asMap(asMap(data)['bill'])));
  }

  /// Rút yêu cầu Void (chỉ khi chưa có hoàn tiền PENDING/SUCCESS).
  Future<Bill> withdrawVoid(int billId, {required String reason}) async {
    final data = await _client.post(
      ApiPaths.billVoidWithdraw(billId),
      data: <String, dynamic>{'reason': reason.trim()},
    );
    return _parse(() => Bill.fromJson(asMap(asMap(data)['bill'])));
  }

  /// Hóa đơn thay thế cho Order đang chờ (sau Void). [discount] cần `BILL_DISCOUNT`.
  Future<Bill> createReplacementBill(
    int orderId, {
    DiscountInput? discount,
  }) async {
    final data = await _client.post(
      ApiPaths.replacementBill(orderId),
      data: discount == null
          ? <String, dynamic>{}
          : <String, dynamic>{'discount': discount.toJson()},
    );
    return _parse(() => Bill.fromJson(asMap(data)));
  }

  /// Danh sách "Cần xử lý": hóa đơn chờ hoàn tiền, order chờ hóa đơn thay thế.
  Future<List<AttentionItem>> attention() async {
    final data = await _client.get(ApiPaths.billingAttention);
    // Bỏ qua loại mục chưa biết (máy chủ mới hơn app) thay vì làm hỏng cả danh sách.
    return _parse(
      () =>
          asMapList(data)
              .where((json) => AttentionType.parse(json['type']) != null)
              .map(AttentionItem.fromJson)
              .toList(),
    );
  }

  /// Ghi nhận hoàn tiền (`REFUND_RECORD`), idempotent theo [requestId].
  Future<RefundResult> recordRefund(
    int billId, {
    required String requestId,
    required PaymentMethod method,
    required int amount,
    required bool confirmed,
    required String reason,
  }) async {
    final data = await _client.post(
      ApiPaths.billRefunds(billId),
      data: <String, dynamic>{
        'requestId': requestId,
        'method': method.apiName,
        'amount': amount,
        'status': confirmed ? 'SUCCESS' : 'PENDING',
        'reason': reason.trim(),
      },
    );
    return _parse(() => RefundResult.fromJson(asMap(data)));
  }

  /// Xác nhận hoàn tiền PENDING → SUCCESS, hoặc FAILED kèm lý do.
  Future<RefundResult> confirmRefund(
    int refundId, {
    required bool success,
    String? failureReason,
  }) async {
    final data = await _client.post(
      ApiPaths.refundConfirm(refundId),
      data: <String, dynamic>{
        'result': success ? 'SUCCESS' : 'FAILED',
        if (!success) 'failureReason': (failureReason ?? '').trim(),
      },
    );
    return _parse(() => RefundResult.fromJson(asMap(data)));
  }

  /// Biên lai hoàn tiền `RF-YYYYMMDD-NNNN` (chỉ giao dịch hoàn SUCCESS; STAFF, MANAGER).
  /// Giao dịch chưa SUCCESS → 409 `RECEIPT_NOT_AVAILABLE`.
  Future<RefundReceipt> refundReceipt(int refundId) async {
    final data = await _client.get(ApiPaths.refundReceipt(refundId));
    return _parse(() => RefundReceipt.fromJson(asMap(data)));
  }

  /// Lịch sử hoàn tiền của hóa đơn.
  Future<List<Refund>> refunds(int billId) async {
    final data = await _client.get(ApiPaths.billRefunds(billId));
    return _parse(() => asMapList(data).map(Refund.fromJson).toList());
  }

  /// Mở lại phiên đang BILLING: order OPEN, bàn OCCUPIED, hóa đơn bị VOIDED.
  /// Cần `PAYMENT_PROCESS` hoặc `SESSION_REOPEN`.
  Future<void> reopenSession(int sessionId, {required String reason}) async {
    await _client.post(
      ApiPaths.sessionReopen(sessionId),
      data: <String, dynamic>{'reason': reason.trim()},
    );
  }
}

/// Giảm giá khi tính tiền (cần `BILL_DISCOUNT`, lý do bắt buộc).
class DiscountInput {
  const DiscountInput({
    required this.kind,
    required this.value,
    required this.reason,
  });

  final DiscountKind kind;

  /// Chuỗi số người dùng nhập (`10`, `12.5`, `50000`).
  final String value;
  final String reason;

  Map<String, dynamic> toJson() => <String, dynamic>{
    'type': kind.apiName,
    'value': num.parse(value.trim().replaceAll(',', '.')),
    'reason': reason.trim(),
  };
}
