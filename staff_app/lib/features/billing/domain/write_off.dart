import '../../orders/domain/order_models.dart';
import 'billing_models.dart';

/// Tổng hợp thông tin ghi nhận thất thu (`WRITTEN_OFF`) của một Order/Bill sau
/// khi Manager đóng cưỡng bức phiên (BR-P15, P-1, P-2). Hàm thuần để test.
///
/// Thất thu không phải doanh thu: số tiền chỉ để theo dõi, không thu thêm,
/// không hoàn tiền, không sửa hóa đơn.
class WriteOffInfo {
  const WriteOffInfo({
    this.shortfallAmount,
    this.totalAmount,
    this.collectedAmount,
    this.billNumber,
    this.reason,
    this.writtenOffAt,
  });

  /// Số tiền thất thu; null khi máy chủ không cung cấp và không có hóa đơn để suy ra.
  final int? shortfallAmount;

  /// Tổng của hóa đơn (chỉ khi đã có Bill).
  final int? totalAmount;

  /// Tiền đã thu ròng trước khi ghi nhận thất thu (chỉ khi đã có Bill).
  final int? collectedAmount;
  final String? billNumber;
  final String? reason;
  final DateTime? writtenOffAt;

  /// Có hóa đơn đi kèm (WRITTEN_OFF cũng xảy ra khi chưa tính tiền, không có Bill).
  bool get hasBill => billNumber != null;

  /// Trả null khi cả Order lẫn Bill đều không ở trạng thái WRITTEN_OFF.
  ///
  /// Ưu tiên số liệu `shortfall_amount` do máy chủ lưu ở Order; nếu thiếu thì suy
  /// ra từ Bill = Tổng - tiền thu ròng (đúng công thức backend).
  static WriteOffInfo? resolve({SessionOrder? order, Bill? bill}) {
    final billWrittenOff = bill != null && bill.isWrittenOff;
    final orderWrittenOff = order != null && order.isWrittenOff;
    if (!billWrittenOff && !orderWrittenOff) return null;
    final writtenOffBill = billWrittenOff ? bill : null;
    return WriteOffInfo(
      shortfallAmount: order?.shortfallAmount ?? writtenOffBill?.shortfallAmount,
      totalAmount: writtenOffBill?.totalAmount,
      collectedAmount: writtenOffBill?.paidAmount,
      billNumber: writtenOffBill?.billNumber,
      reason: order?.writeoffReason,
      writtenOffAt: order?.writtenOffAt ?? writtenOffBill?.writtenOffAt,
    );
  }
}
