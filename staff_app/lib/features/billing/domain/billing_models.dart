import '../../../core/util/json_utils.dart';

T? _parseEnum<T>(Iterable<T> values, String Function(T) name, Object? raw) {
  final text = asStringOrNull(raw);
  for (final value in values) {
    if (name(value) == text) return value;
  }
  return null;
}

/// Phương thức thanh toán (backend `PaymentMethod`).
enum PaymentMethod {
  cash('CASH', 'Tiền mặt'),
  bankTransfer('BANK_TRANSFER', 'Chuyển khoản'),
  cardPos('CARD_POS', 'Quẹt thẻ (POS)'),
  other('OTHER', 'Khác');

  const PaymentMethod(this.apiName, this.label);

  final String apiName;
  final String label;

  static PaymentMethod? parse(Object? raw) =>
      _parseEnum(PaymentMethod.values, (e) => e.apiName, raw);
}

/// Trạng thái giao dịch tiền (backend `TransactionStatus`).
enum TransactionStatus {
  pending('PENDING', 'Chờ xác nhận'),
  success('SUCCESS', 'Thành công'),
  failed('FAILED', 'Thất bại');

  const TransactionStatus(this.apiName, this.label);

  final String apiName;
  final String label;

  static TransactionStatus? parse(Object? raw) =>
      _parseEnum(TransactionStatus.values, (e) => e.apiName, raw);
}

enum BillStatus {
  active('ACTIVE', 'Đang hiệu lực'),
  voidPending('VOID_PENDING', 'Chờ hoàn tiền (Void)'),
  voided('VOIDED', 'Đã Void'),
  writtenOff('WRITTEN_OFF', 'Ghi nhận thất thu');

  const BillStatus(this.apiName, this.label);

  final String apiName;
  final String label;

  static BillStatus? parse(Object? raw) =>
      _parseEnum(BillStatus.values, (e) => e.apiName, raw);
}

enum BillPaymentStatus {
  unpaid('UNPAID', 'Chưa thanh toán'),
  partiallyPaid('PARTIALLY_PAID', 'Thanh toán một phần'),
  paid('PAID', 'Đã thanh toán');

  const BillPaymentStatus(this.apiName, this.label);

  final String apiName;
  final String label;

  static BillPaymentStatus? parse(Object? raw) =>
      _parseEnum(BillPaymentStatus.values, (e) => e.apiName, raw);
}

/// Dòng món trên Bill (backend `BillLineResponse`).
class BillLine {
  const BillLine({
    required this.itemId,
    required this.productName,
    required this.options,
    required this.quantity,
    required this.unitPrice,
    required this.lineTotal,
    this.note,
  });

  factory BillLine.fromJson(Map<String, dynamic> json) {
    final options = json['options'];
    return BillLine(
      itemId: asInt(json['itemId']),
      productName: asString(json['productName']),
      options: options is List
          ? options.map((e) => e.toString()).toList()
          : const <String>[],
      note: asStringOrNull(json['note']),
      quantity: asInt(json['quantity']),
      unitPrice: asInt(json['unitPrice']),
      lineTotal: asInt(json['lineTotal']),
    );
  }

  final int itemId;
  final String productName;
  final List<String> options;
  final String? note;
  final int quantity;
  final int unitPrice;
  final int lineTotal;
}

/// Giao dịch thu tiền (backend `PaymentResponse`).
class Payment {
  const Payment({
    required this.id,
    required this.billId,
    required this.method,
    required this.amount,
    required this.status,
    this.receiptNumber,
    this.provider,
    this.providerTxnId,
    this.note,
    this.failureReason,
    this.createdAt,
    this.confirmedAt,
  });

  factory Payment.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    final method = PaymentMethod.parse(json['method']);
    final status = TransactionStatus.parse(json['status']);
    if (id == null || method == null || status == null) {
      throw const FormatException('Dữ liệu giao dịch không hợp lệ.');
    }
    return Payment(
      id: id,
      billId: asInt(json['billId']),
      receiptNumber: asStringOrNull(json['receiptNumber']),
      method: method,
      amount: asInt(json['amount']),
      status: status,
      provider: asStringOrNull(json['provider']),
      providerTxnId: asStringOrNull(json['providerTxnId']),
      note: asStringOrNull(json['note']),
      failureReason: asStringOrNull(json['failureReason']),
      createdAt: asDateTime(json['createdAt']),
      confirmedAt: asDateTime(json['confirmedAt']),
    );
  }

  final int id;
  final int billId;
  final String? receiptNumber;
  final PaymentMethod method;
  final int amount;
  final TransactionStatus status;
  final String? provider;
  final String? providerTxnId;
  final String? note;
  final String? failureReason;
  final DateTime? createdAt;
  final DateTime? confirmedAt;
}

/// Thông tin chuyển khoản VietQR của một Bill còn tiền phải thu (backend `BankTransferInfo`).
/// Chỉ có khi nhà hàng đã cấu hình tài khoản nhận tiền và Bill đang hiệu lực.
class BankTransferInfo {
  const BankTransferInfo({
    required this.bankBin,
    required this.accountNo,
    required this.accountName,
    required this.amount,
    required this.transferNote,
    required this.qrImageUrl,
  });

  /// Null khi thiếu số tài khoản hoặc URL ảnh không phải http(s): không hiển thị QR còn hơn hiển thị sai.
  static BankTransferInfo? tryParse(Object? raw) {
    if (raw is! Map) return null;
    final json = asMap(raw);
    final url = asString(json['qrImageUrl']);
    final accountNo = asString(json['accountNo']);
    final uri = Uri.tryParse(url);
    final isWeb = uri != null && (uri.scheme == 'https' || uri.scheme == 'http');
    if (!isWeb || accountNo.isEmpty) return null;
    return BankTransferInfo(
      bankBin: asString(json['bankBin']),
      accountNo: accountNo,
      accountName: asString(json['accountName']),
      amount: asInt(json['amount']),
      transferNote: asString(json['transferNote']),
      qrImageUrl: url,
    );
  }

  final String bankBin;
  final String accountNo;
  final String accountName;

  /// Số tiền còn phải thu mà [qrImageUrl] đã gắn sẵn.
  final int amount;

  /// Nội dung chuyển khoản = số hóa đơn.
  final String transferNote;
  final String qrImageUrl;

  static final RegExp _amountParam = RegExp(r'([?&])amount=[^&#]*');

  /// URL ảnh QR với số tiền khác (thu một phần). Chỉ thay tham số `amount`, giữ nguyên phần còn lại
  /// (nội dung, tên chủ tài khoản đã mã hóa) do máy chủ dựng.
  String imageUrlFor(int newAmount) {
    if (newAmount == amount) return qrImageUrl;
    if (_amountParam.hasMatch(qrImageUrl)) {
      return qrImageUrl.replaceFirstMapped(
        _amountParam,
        (match) => '${match[1]}amount=$newAmount',
      );
    }
    final separator = qrImageUrl.contains('?') ? '&' : '?';
    return '$qrImageUrl${separator}amount=$newAmount';
  }
}

/// Bill cho nhân viên (backend `BillResponse`); tiền là snapshot, số nguyên VND.
class Bill {
  const Bill({
    required this.id,
    required this.billNumber,
    required this.orderId,
    required this.sessionId,
    required this.tableId,
    required this.tableCode,
    required this.status,
    required this.paymentStatus,
    required this.subtotalAmount,
    required this.discountAmount,
    required this.netAmount,
    required this.serviceFeeAmount,
    required this.totalAmount,
    required this.vatAmount,
    required this.paidAmount,
    required this.pendingAmount,
    required this.remainingAmount,
    required this.lines,
    required this.payments,
    required this.version,
    this.discountReason,
    this.createdAt,
    this.paidAt,
    this.refundedAmount = 0,
    this.refundPendingAmount = 0,
    this.remainingRefundable = 0,
    this.voidReason,
    this.writtenOffAt,
    this.bankTransfer,
  });

  factory Bill.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    final status = BillStatus.parse(json['status']);
    if (id == null || status == null) {
      throw const FormatException('Dữ liệu hóa đơn không hợp lệ.');
    }
    return Bill(
      id: id,
      billNumber: asString(json['billNumber']),
      orderId: asInt(json['orderId']),
      sessionId: asInt(json['sessionId']),
      tableId: asInt(json['tableId']),
      tableCode: asString(json['tableCode']),
      status: status,
      paymentStatus: BillPaymentStatus.parse(json['paymentStatus']) ??
          BillPaymentStatus.unpaid,
      subtotalAmount: asInt(json['subtotalAmount']),
      discountAmount: asInt(json['discountAmount']),
      discountReason: asStringOrNull(json['discountReason']),
      netAmount: asInt(json['netAmount']),
      serviceFeeAmount: asInt(json['serviceFeeAmount']),
      totalAmount: asInt(json['totalAmount']),
      vatAmount: asInt(json['vatAmount']),
      paidAmount: asInt(json['paidAmount']),
      pendingAmount: asInt(json['pendingAmount']),
      remainingAmount: asInt(json['remainingAmount']),
      createdAt: asDateTime(json['createdAt']),
      paidAt: asDateTime(json['paidAt']),
      lines: asMapList(json['lines']).map(BillLine.fromJson).toList(),
      payments: asMapList(json['payments']).map(Payment.fromJson).toList(),
      version: asInt(json['version']),
      refundedAmount: asInt(json['refundedAmount']),
      refundPendingAmount: asInt(json['refundPendingAmount']),
      remainingRefundable: asInt(json['remainingRefundable']),
      voidReason: asStringOrNull(json['voidReason']),
      writtenOffAt: asDateTime(json['writtenOffAt']),
      bankTransfer: BankTransferInfo.tryParse(json['bankTransfer']),
    );
  }

  final int id;
  final String billNumber;
  final int orderId;
  final int sessionId;
  final int tableId;
  final String tableCode;
  final BillStatus status;
  final BillPaymentStatus paymentStatus;
  final int subtotalAmount;
  final int discountAmount;
  final String? discountReason;
  final int netAmount;
  final int serviceFeeAmount;
  final int totalAmount;
  final int vatAmount;
  final int paidAmount;
  final int pendingAmount;
  final int remainingAmount;
  final DateTime? createdAt;
  final DateTime? paidAt;
  final List<BillLine> lines;
  final List<Payment> payments;
  final int version;

  /// Đã hoàn thành công (TRS) / đang chờ hoàn (TRP) / còn có thể hoàn.
  final int refundedAmount;
  final int refundPendingAmount;
  final int remainingRefundable;
  final String? voidReason;

  /// Thời điểm ghi nhận thất thu khi force-close (chỉ Bill WRITTEN_OFF).
  final DateTime? writtenOffAt;

  /// VietQR cho số tiền còn phải thu; null = chưa cấu hình ngân hàng, đã thu đủ hoặc máy chủ cũ.
  final BankTransferInfo? bankTransfer;

  /// Hóa đơn đã được ghi nhận thất thu: trạng thái cuối, không thu/hoàn/sửa (P-2).
  bool get isWrittenOff => status == BillStatus.writtenOff;

  /// Thất thu của Bill WRITTEN_OFF = Tổng - tiền thu ròng (BR-P15); luôn >= 0.
  /// `paidAmount` là tiền thu ròng (TPS - TRS). Null khi Bill chưa bị ghi nhận thất thu.
  int? get shortfallAmount {
    if (!isWrittenOff) return null;
    final value = totalAmount - paidAmount;
    return value < 0 ? 0 : value;
  }

  /// Chờ hoàn tiền sau yêu cầu Void (BR-P09).
  bool get isVoidPending => status == BillStatus.voidPending;

  /// Còn tiền phải hoàn cho khách (sau khi trừ khoản đang chờ).
  bool get needsRefund => isVoidPending && remainingRefundable > 0;

  bool get isPaid => paymentStatus == BillPaymentStatus.paid;

  /// Còn có thể ghi nhận thêm tiền thu (Bill hiệu lực và còn số dư).
  bool get canRecordPayment =>
      status == BillStatus.active && remainingAmount > 0;

  List<Payment> get pendingPayments => payments
      .where((payment) => payment.status == TransactionStatus.pending)
      .toList();
}

/// Kết quả ghi nhận/xác nhận thanh toán (backend `PaymentResultResponse`).
class PaymentResult {
  const PaymentResult({
    required this.payment,
    required this.billPaymentStatus,
    required this.remainingAmount,
    required this.sessionClosed,
    required this.replayed,
  });

  factory PaymentResult.fromJson(Map<String, dynamic> json) {
    final bill = asMap(json['bill']);
    return PaymentResult(
      payment: Payment.fromJson(asMap(json['payment'])),
      billPaymentStatus: BillPaymentStatus.parse(bill['paymentStatus']) ??
          BillPaymentStatus.unpaid,
      remainingAmount: asInt(bill['remainingAmount']),
      sessionClosed: asBool(json['sessionClosed']),
      replayed: asBool(json['replayed']),
    );
  }

  final Payment payment;
  final BillPaymentStatus billPaymentStatus;
  final int remainingAmount;

  /// Giao dịch này làm phiên đóng (Bill PAID và món đã xong): bàn chuyển Đang dọn.
  final bool sessionClosed;
  final bool replayed;
}

/// Dữ liệu in phiếu thanh toán nội bộ (backend `PaymentReceiptResponse`).
class PaymentReceipt {
  const PaymentReceipt({
    required this.restaurantName,
    required this.receiptNumber,
    required this.billNumber,
    required this.tableCode,
    required this.method,
    required this.amount,
    required this.lines,
    required this.subtotalAmount,
    required this.discountAmount,
    required this.serviceFeeAmount,
    required this.vatAmount,
    required this.totalAmount,
    required this.paidAfter,
    required this.remainingAfter,
    required this.footer,
    this.address,
    this.phone,
    this.issuedAt,
    this.cashierName,
  });

  factory PaymentReceipt.fromJson(Map<String, dynamic> json) {
    final header = asMap(json['header']);
    return PaymentReceipt(
      restaurantName: asString(header['restaurantName']),
      address: asStringOrNull(header['address']),
      phone: asStringOrNull(header['phone']),
      receiptNumber: asString(json['receiptNumber']),
      issuedAt: asDateTime(json['issuedAt']),
      billNumber: asString(json['billNumber']),
      tableCode: asString(json['tableCode']),
      cashierName: asStringOrNull(json['cashierName']),
      method: PaymentMethod.parse(json['method']) ?? PaymentMethod.other,
      amount: asInt(json['amount']),
      lines: asMapList(json['lines']).map(BillLine.fromJson).toList(),
      subtotalAmount: asInt(json['subtotalAmount']),
      discountAmount: asInt(json['discountAmount']),
      serviceFeeAmount: asInt(json['serviceFeeAmount']),
      vatAmount: asInt(json['vatAmount']),
      totalAmount: asInt(json['totalAmount']),
      paidAfter: asInt(json['paidAfter']),
      remainingAfter: asInt(json['remainingAfter']),
      footer: asString(json['footer']),
    );
  }

  final String restaurantName;
  final String? address;
  final String? phone;
  final String receiptNumber;
  final DateTime? issuedAt;
  final String billNumber;
  final String tableCode;
  final String? cashierName;
  final PaymentMethod method;
  final int amount;
  final List<BillLine> lines;
  final int subtotalAmount;
  final int discountAmount;
  final int serviceFeeAmount;
  final int vatAmount;
  final int totalAmount;
  final int paidAfter;
  final int remainingAfter;
  final String footer;
}

/// Hình thức giảm giá khi tính tiền (backend `DiscountType`, trừ `NONE`).
enum DiscountKind {
  percent('PERCENT', 'Phần trăm (%)'),
  fixedAmount('FIXED_AMOUNT', 'Số tiền cố định (đ)');

  const DiscountKind(this.apiName, this.label);

  final String apiName;
  final String label;
}

/// Kiểm tra giảm giá phía client (backend vẫn là nơi quyết định):
/// PERCENT: 0 < x <= 100; FIXED_AMOUNT: số nguyên > 0 và <= [subtotal] nếu biết.
String? validateDiscountValue(DiscountKind kind, String raw, {int? subtotal}) {
  final text = raw.trim().replaceAll(',', '.');
  final value = num.tryParse(text);
  if (value == null || value <= 0) return 'Nhập giá trị giảm lớn hơn 0';
  if (kind == DiscountKind.percent) {
    if (value > 100) return 'Phần trăm tối đa 100';
    final dot = text.indexOf('.');
    if (dot >= 0 && text.length - dot - 1 > 2) {
      return 'Tối đa 2 chữ số thập phân';
    }
  } else {
    if (value != value.truncate()) return 'Số tiền phải là số nguyên (đ)';
    if (subtotal != null && value > subtotal) {
      return 'Giảm không được vượt tạm tính';
    }
  }
  return null;
}

/// Một giao dịch hoàn tiền (backend `RefundResponse`).
class Refund {
  const Refund({
    required this.id,
    required this.billId,
    required this.method,
    required this.amount,
    required this.status,
    required this.reason,
    this.receiptNumber,
    this.failureReason,
    this.createdAt,
    this.confirmedAt,
  });

  factory Refund.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    if (id == null) throw const FormatException('Giao dịch hoàn không hợp lệ.');
    return Refund(
      id: id,
      billId: asInt(json['billId']),
      receiptNumber: asStringOrNull(json['receiptNumber']),
      method: PaymentMethod.parse(json['method']) ?? PaymentMethod.other,
      amount: asInt(json['amount']),
      status:
          TransactionStatus.parse(json['status']) ?? TransactionStatus.pending,
      reason: asString(json['reason']),
      failureReason: asStringOrNull(json['failureReason']),
      createdAt: asDateTime(json['createdAt']),
      confirmedAt: asDateTime(json['confirmedAt']),
    );
  }

  final int id;
  final int billId;
  final String? receiptNumber;
  final PaymentMethod method;
  final int amount;
  final TransactionStatus status;
  final String reason;
  final String? failureReason;
  final DateTime? createdAt;
  final DateTime? confirmedAt;

  /// Có biên lai để xem: chỉ giao dịch hoàn SUCCESS đã được cấp mã `RF-`.
  bool get hasReceipt =>
      status == TransactionStatus.success && (receiptNumber ?? '').isNotEmpty;
}

/// Kết quả ghi nhận/xác nhận hoàn tiền (backend `RefundResultResponse`).
class RefundResult {
  const RefundResult({
    required this.refund,
    required this.billStatus,
    required this.remainingRefundable,
    required this.voidCompleted,
    required this.replayed,
  });

  factory RefundResult.fromJson(Map<String, dynamic> json) {
    final summary = asMap(json['summary']);
    return RefundResult(
      refund: Refund.fromJson(asMap(json['refund'])),
      billStatus: BillStatus.parse(summary['billStatus']) ?? BillStatus.active,
      remainingRefundable: asInt(summary['remainingRefundable']),
      voidCompleted: asBool(json['voidCompleted']),
      replayed: asBool(json['replayed']),
    );
  }

  final Refund refund;
  final BillStatus billStatus;
  final int remainingRefundable;

  /// Lần hoàn này đã đủ: Bill chuyển VOIDED.
  final bool voidCompleted;
  final bool replayed;
}

/// Dữ liệu biên lai hoàn tiền `RF-YYYYMMDD-NNNN` (backend `RefundReceiptResponse`).
/// Chỉ có với giao dịch hoàn SUCCESS.
class RefundReceipt {
  const RefundReceipt({
    required this.restaurantName,
    required this.receiptNumber,
    required this.billNumber,
    required this.billStatus,
    required this.tableCode,
    required this.method,
    required this.amount,
    required this.reason,
    required this.billTotalAmount,
    required this.totalPaidSuccess,
    required this.refundedBefore,
    required this.refundedAfter,
    required this.remainingToRefund,
    required this.footer,
    this.address,
    this.phone,
    this.taxCode,
    this.issuedAt,
    this.tableName,
    this.managerName,
    this.voidReason,
  });

  factory RefundReceipt.fromJson(Map<String, dynamic> json) {
    final number = asStringOrNull(json['receiptNumber']);
    if (number == null) {
      throw const FormatException('Biên lai hoàn tiền không hợp lệ.');
    }
    final header = asMap(json['header']);
    return RefundReceipt(
      restaurantName: asString(header['restaurantName']),
      address: asStringOrNull(header['address']),
      phone: asStringOrNull(header['phone']),
      taxCode: asStringOrNull(header['taxCode']),
      receiptNumber: number,
      issuedAt: asDateTime(json['issuedAt']),
      billNumber: asString(json['billNumber']),
      billStatus: BillStatus.parse(json['billStatus']) ?? BillStatus.active,
      tableCode: asString(json['tableCode']),
      tableName: asStringOrNull(json['tableName']),
      managerName: asStringOrNull(json['managerName']),
      method: PaymentMethod.parse(json['method']) ?? PaymentMethod.other,
      amount: asInt(json['amount']),
      reason: asString(json['reason']),
      voidReason: asStringOrNull(json['voidReason']),
      billTotalAmount: asInt(json['billTotalAmount']),
      totalPaidSuccess: asInt(json['totalPaidSuccess']),
      refundedBefore: asInt(json['refundedBefore']),
      refundedAfter: asInt(json['refundedAfter']),
      remainingToRefund: asInt(json['remainingToRefund']),
      footer: asString(json['footer']),
    );
  }

  final String restaurantName;
  final String? address;
  final String? phone;
  final String? taxCode;
  final String receiptNumber;
  final DateTime? issuedAt;
  final String billNumber;
  final BillStatus billStatus;
  final String tableCode;
  final String? tableName;
  final String? managerName;
  final PaymentMethod method;

  /// Số tiền của lần hoàn này.
  final int amount;
  final String reason;
  final String? voidReason;
  final int billTotalAmount;

  /// Tổng tiền đã thu thành công của Bill (TPS).
  final int totalPaidSuccess;

  /// Tổng đã hoàn trước / sau lần hoàn này (theo thứ tự xác nhận).
  final int refundedBefore;
  final int refundedAfter;
  final int remainingToRefund;
  final String footer;

  /// Đã hoàn đủ toàn bộ tiền đã thu của Bill.
  bool get isFullyRefunded => remainingToRefund <= 0;
}

/// Loại mục "Cần xử lý" của Manager.
enum AttentionType {
  billVoidPending('BILL_VOID_PENDING', 'Chờ hoàn tiền'),
  awaitingReplacement('AWAITING_REPLACEMENT_BILL', 'Chờ hóa đơn thay thế'),
  awaitingPayment('BILL_AWAITING_PAYMENT', 'Chờ thu tiền');

  const AttentionType(this.apiName, this.label);

  final String apiName;
  final String label;

  static AttentionType? parse(Object? raw) {
    final text = raw?.toString();
    for (final value in values) {
      if (value.apiName == text) return value;
    }
    return null;
  }
}

/// Mục "Cần xử lý" (backend `BillingAttentionItem`).
class AttentionItem {
  const AttentionItem({
    required this.type,
    required this.orderId,
    required this.sessionId,
    this.tableCode,
    this.billId,
    this.billNumber,
    this.since,
    this.remainingRefundable,
    this.reason,
    this.remainingAmount,
  });

  factory AttentionItem.fromJson(Map<String, dynamic> json) {
    final type = AttentionType.parse(json['type']);
    if (type == null) {
      throw const FormatException('Mục cần xử lý không hợp lệ.');
    }
    return AttentionItem(
      type: type,
      orderId: asInt(json['orderId']),
      sessionId: asInt(json['sessionId']),
      tableCode: asStringOrNull(json['tableCode']),
      billId: asIntOrNull(json['billId']),
      billNumber: asStringOrNull(json['billNumber']),
      since: asDateTime(json['since']),
      remainingRefundable: asIntOrNull(json['remainingRefundable']),
      reason: asStringOrNull(json['reason']),
      remainingAmount: asIntOrNull(json['remainingAmount']),
    );
  }

  final AttentionType type;
  final int orderId;
  final int sessionId;
  final String? tableCode;
  final int? billId;
  final String? billNumber;
  final DateTime? since;
  final int? remainingRefundable;
  final String? reason;

  /// Chỉ có với [AttentionType.awaitingPayment]: số còn phải thu.
  final int? remainingAmount;
}

/// Gom các mục "Cần xử lý" theo loại, giữ thứ tự khai báo của [AttentionType]
/// và thứ tự gốc (cũ trước) trong từng nhóm. Nhóm rỗng bị bỏ.
Map<AttentionType, List<AttentionItem>> groupAttention(
  List<AttentionItem> items,
) {
  final result = <AttentionType, List<AttentionItem>>{};
  for (final type in AttentionType.values) {
    final group = items.where((item) => item.type == type).toList();
    if (group.isNotEmpty) result[type] = group;
  }
  return result;
}
