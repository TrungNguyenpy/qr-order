package com.qrorder.billing.service

import com.qrorder.billing.domain.Bill
import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.domain.PaymentStatus
import com.qrorder.billing.domain.PaymentStatusRule
import com.qrorder.billing.domain.PaymentTransaction
import com.qrorder.billing.domain.RefundTransaction
import com.qrorder.billing.domain.TransactionStatus
import com.qrorder.billing.dto.BillLineResponse
import com.qrorder.billing.dto.BillResponse
import com.qrorder.billing.dto.BillSummaryResponse
import com.qrorder.billing.dto.GuestBillResponse
import com.qrorder.billing.dto.PaymentResponse
import com.qrorder.billing.dto.RefundResponse
import com.qrorder.billing.dto.RefundSummaryResponse
import com.qrorder.billing.event.BillChangeKind
import com.qrorder.billing.event.BillChangedEvent
import com.qrorder.billing.repository.BillRepository
import com.qrorder.billing.repository.PaymentTransactionRepository
import com.qrorder.billing.repository.RefundTransactionRepository
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.order.domain.Order
import com.qrorder.order.domain.OrderItem
import com.qrorder.order.repository.OrderItemOptionRepository
import com.qrorder.order.repository.OrderItemRepository
import com.qrorder.order.repository.OrderRepository
import com.qrorder.order.service.LockedSession
import com.qrorder.order.service.SessionOrderLocker
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.repository.TableSessionRepository
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Action audit_log của module billing (DATABASE_DESIGN.md §6; BILL_DISCOUNT_APPLIED bắt buộc lý do ở DB). */
object BillingAuditActions {
    const val BILL_CREATED = "BILL_CREATED"
    const val BILL_DISCOUNT_APPLIED = "BILL_DISCOUNT_APPLIED"
    const val BILL_VOIDED = "BILL_VOIDED"
    const val BILL_PAID = "BILL_PAID"
    const val BILL_WRITTEN_OFF = "BILL_WRITTEN_OFF"
    const val PAYMENT_RECORDED = "PAYMENT_RECORDED"
    const val PAYMENT_CONFIRMED = "PAYMENT_CONFIRMED"
    const val PAYMENT_FAILED = "PAYMENT_FAILED"
    const val SESSION_REOPENED = "SESSION_REOPENED"
    const val SESSION_CLOSED = "SESSION_CLOSED"
    /** GĐ5b. Ba action dưới bắt buộc lý do (CHECK ck_audit_log_reason_required). */
    const val BILL_VOID_REQUESTED = "BILL_VOID_REQUESTED"
    const val BILL_VOID_WITHDRAWN = "BILL_VOID_WITHDRAWN"
    const val REFUND_CREATED = "REFUND_CREATED"
    const val REFUND_CONFIRMED = "REFUND_CONFIRMED"
    const val REFUND_FAILED = "REFUND_FAILED"
    const val BILL_REPLACEMENT_CREATED = "BILL_REPLACEMENT_CREATED"
}

object BillingAuditEntityTypes {
    const val BILL = "bill"
    const val PAYMENT_TRANSACTION = "payment_transaction"
    const val REFUND_TRANSACTION = "refund_transaction"
    const val TABLE_SESSION = "table_session"
    const val ORDERS = "orders"
}

/** Bill hiện hành = ACTIVE hoặc VOID_PENDING (ux_bill_current_per_order, BR-P06). */
val CURRENT_BILL_STATUSES: Set<BillStatus> = setOf(BillStatus.ACTIVE, BillStatus.VOID_PENDING)

/**
 * Tổng tiền theo trạng thái giao dịch của một Bill (BR-P13): TPS, TPP (Payment SUCCESS/PENDING), TRS, TRP
 * (Refund SUCCESS/PENDING). FAILED không tính.
 */
data class MoneySummary(val tps: Long, val tpp: Long, val trs: Long, val trp: Long) {
    /** Tiền thu ròng (BR-P11). */
    val netCollected: Long get() = tps - trs

    /** Còn có thể ghi nhận thêm (BR-P10, cùng công thức trigger fn_money_txn_validate): Total - (TPS + TPP - TRS). */
    fun remaining(total: Long): Long = maxOf(0L, total - (tps + tpp - trs))

    /** BR-P13: số còn được hoàn = TPS - TRS - TRP (Refund FAILED không tính). */
    val remainingRefundable: Long get() = maxOf(0L, tps - trs - trp)

    /** BR-P13 (5): Void hoàn tất khi TRS = TPS và không còn Refund PENDING. */
    val fullyRefunded: Boolean get() = trs == tps && trp == 0L

    /** Còn phải thu theo tiền đã thu thật (không tính PENDING). */
    fun outstanding(total: Long): Long = maxOf(0L, total - netCollected)

    fun paymentStatus(total: Long): PaymentStatus = PaymentStatusRule.of(netCollected, total)

    companion object {
        fun of(payments: List<PaymentTransaction>, refunds: List<RefundTransaction>) = MoneySummary(
            tps = payments.filter { it.status == TransactionStatus.SUCCESS }.sumOf { it.amount },
            tpp = payments.filter { it.status == TransactionStatus.PENDING }.sumOf { it.amount },
            trs = refunds.filter { it.status == TransactionStatus.SUCCESS }.sumOf { it.amount },
            trp = refunds.filter { it.status == TransactionStatus.PENDING }.sumOf { it.amount },
        )
    }
}

/** Ngữ cảnh đã khóa của một Bill: bàn + phiên (FOR UPDATE), Order và Bill nạp lại sau khi khóa. */
class BillContext(val locked: LockedSession, val order: Order, val bill: Bill) {
    val billId: Long get() = requireNotNull(bill.id)
    val orderId: Long get() = requireNotNull(order.id)
}

/**
 * Khóa theo đúng thứ tự chung của dự án (dòng bàn -> dòng phiên, [SessionOrderLocker]) rồi mới nạp Order/Bill, nên mọi
 * thao tác ghi của billing được tuần tự hóa với đặt món, xử lý món, force-close và Scheduler trên cùng bàn (không
 * deadlock). Trigger `fn_money_txn_validate` khóa thêm dòng Bill (FOR UPDATE) làm hàng rào cuối cho thu tiền đồng thời.
 */
@Component
class BillingLocker(
    private val locker: SessionOrderLocker,
    private val orderRepository: OrderRepository,
    private val billRepository: BillRepository,
    private val sessionRepository: TableSessionRepository,
) {
    fun lockOrder(orderId: Long): Pair<LockedSession, Order> {
        val sessionId = orderRepository.findSessionIdById(orderId) ?: throw ResourceNotFoundException("order", orderId)
        val locked = locker.lock(sessionId)
        val order = orderRepository.findById(orderId).orElseThrow { ResourceNotFoundException("order", orderId) }
        return locked to order
    }

    fun lockBill(billId: Long): BillContext {
        val orderId = billRepository.findOrderIdById(billId) ?: throw ResourceNotFoundException("Bill", billId)
        val (locked, order) = lockOrder(orderId)
        val bill = billRepository.findById(billId).orElseThrow { ResourceNotFoundException("Bill", billId) }
        return BillContext(locked, order, bill)
    }

    fun lockSession(sessionId: Long): LockedSession = locker.lock(sessionId)

    fun saveSession(locked: LockedSession) = locker.save(locked.session)

    /** Đọc trạng thái phiên không khóa (phản hồi của request gửi lại - idempotent). */
    fun sessionStatusOf(sessionId: Long): SessionStatus =
        sessionRepository.findById(sessionId).orElseThrow { ResourceNotFoundException("phiên bàn", sessionId) }.status
}

/** Dựng phản hồi Bill / Payment và sự kiện (tải option theo lô, không N+1). */
@Component
class BillViewMapper(
    private val itemRepository: OrderItemRepository,
    private val optionRepository: OrderItemOptionRepository,
    private val paymentRepository: PaymentTransactionRepository,
    private val refundRepository: RefundTransactionRepository,
) {
    fun money(billId: Long): MoneySummary =
        MoneySummary.of(paymentRepository.findAllByBillIdOrderByIdAsc(billId), refundRepository.findAllByBillIdOrderByIdAsc(billId))

    /** Món tính tiền hiện tại của Order (CONFIRMED, PREPARING, READY, SERVED), kèm tên option. */
    fun lines(orderId: Long): List<BillLineResponse> = lines(itemRepository.findAllByOrderIdOrderByIdAsc(orderId))

    fun lines(items: List<OrderItem>): List<BillLineResponse> {
        val billable = items.filter { it.status.isBillable }
        if (billable.isEmpty()) return emptyList()
        val options = optionRepository.findAllByOrderItemIdInOrderByIdAsc(billable.map { requireNotNull(it.id) }).groupBy { it.orderItemId }
        return billable.map { i ->
            BillLineResponse(
                itemId = requireNotNull(i.id), productName = i.productName,
                options = options[i.id].orEmpty().map { "${it.groupName}: ${it.optionName}" },
                note = i.note, quantity = i.quantity, unitPrice = i.unitPrice + i.optionExtraPerUnit,
                lineTotal = i.lineTotal, status = i.status,
            )
        }
    }

    fun payment(p: PaymentTransaction) = PaymentResponse(
        id = requireNotNull(p.id), billId = p.billId, requestId = p.requestId, receiptNumber = p.receiptNumber, method = p.method,
        amount = p.amount, status = p.status, provider = p.provider, providerTxnId = p.providerTxnId, note = p.note,
        failureReason = p.failureReason, createdBy = p.createdBy, createdAt = p.createdAt, confirmedBy = p.confirmedBy,
        confirmedAt = p.confirmedAt,
    )

    fun bill(bill: Bill, order: Order, locked: LockedSessionView): BillResponse {
        val billId = requireNotNull(bill.id)
        val payments = paymentRepository.findAllByBillIdOrderByIdAsc(billId)
        val refunds = refundRepository.findAllByBillIdOrderByIdAsc(billId)
        val money = MoneySummary.of(payments, refunds)
        return BillResponse(
            id = billId, billNumber = requireNotNull(bill.billNumber), orderId = bill.orderId, orderStatus = order.status,
            sessionId = locked.sessionId, sessionStatus = locked.sessionStatus, tableId = locked.tableId, tableCode = locked.tableCode,
            status = bill.status, paymentStatus = bill.paymentStatus, subtotalAmount = bill.subtotalAmount,
            discountAmount = bill.discountAmount, discountReason = bill.discountReason, discountedBy = bill.discountedBy,
            netAmount = bill.netAmount, serviceFeeRate = bill.serviceFeeRate, serviceFeeTaxable = bill.serviceFeeTaxable,
            serviceFeeAmount = bill.serviceFeeAmount, totalAmount = bill.totalAmount, vatRate = bill.vatRate, vatAmount = bill.vatAmount,
            paidAmount = money.netCollected, pendingAmount = money.tpp, remainingAmount = money.remaining(bill.totalAmount),
            replacesBillId = bill.replacesBillId, createdBy = bill.createdBy, createdAt = bill.createdAt, paidAt = bill.paidAt,
            voidType = bill.voidType, voidReason = bill.voidReason, voidedAt = bill.voidedAt, writtenOffAt = bill.writtenOffAt,
            lines = if (bill.status == BillStatus.VOIDED) emptyList() else lines(bill.orderId),
            payments = payments.map(::payment), version = bill.version,
            refundedAmount = money.trs, refundPendingAmount = money.trp, remainingRefundable = money.remainingRefundable,
            voidRequestedAt = bill.voidRequestedAt, orderNeedsAttention = order.needsAttention, refunds = refunds.map(::refund),
        )
    }

    fun refund(r: RefundTransaction) = RefundResponse(
        id = requireNotNull(r.id), billId = r.billId, requestId = r.requestId, receiptNumber = r.receiptNumber, method = r.method,
        amount = r.amount, status = r.status, reason = r.reason, failureReason = r.failureReason, createdBy = r.createdBy,
        createdAt = r.createdAt, confirmedBy = r.confirmedBy, confirmedAt = r.confirmedAt,
    )

    fun refundSummary(bill: Bill, money: MoneySummary = money(requireNotNull(bill.id))) = RefundSummaryResponse(
        billId = requireNotNull(bill.id), billNumber = requireNotNull(bill.billNumber), billStatus = bill.status,
        paymentStatus = bill.paymentStatus, totalPaidSuccess = money.tps, refundedAmount = money.trs,
        refundPendingAmount = money.trp, remainingRefundable = money.remainingRefundable, netCollected = money.netCollected,
    )

    fun guestBill(bill: Bill): GuestBillResponse {
        val money = money(requireNotNull(bill.id))
        return GuestBillResponse(
            billNumber = requireNotNull(bill.billNumber), status = bill.status, paymentStatus = bill.paymentStatus,
            subtotalAmount = bill.subtotalAmount, discountAmount = bill.discountAmount, netAmount = bill.netAmount,
            serviceFeeAmount = bill.serviceFeeAmount, totalAmount = bill.totalAmount, vatRate = bill.vatRate, vatAmount = bill.vatAmount,
            paidAmount = money.netCollected, remainingAmount = money.outstanding(bill.totalAmount), lines = lines(bill.orderId),
            createdAt = bill.createdAt,
        )
    }

    fun summary(bill: Bill, money: MoneySummary = money(requireNotNull(bill.id))) = BillSummaryResponse(
        id = requireNotNull(bill.id), billNumber = requireNotNull(bill.billNumber), status = bill.status,
        paymentStatus = bill.paymentStatus, totalAmount = bill.totalAmount, paidAmount = money.netCollected,
        pendingAmount = money.tpp, remainingAmount = money.remaining(bill.totalAmount), version = bill.version,
    )

    fun event(kind: BillChangeKind, locked: LockedSession, bill: Bill, money: MoneySummary = money(requireNotNull(bill.id))) =
        BillChangedEvent(
            kind = kind, sessionId = locked.sessionId, tableId = locked.tableId, tableCode = locked.table.code,
            orderId = bill.orderId, billId = requireNotNull(bill.id), billNumber = requireNotNull(bill.billNumber),
            billStatus = bill.status, paymentStatus = bill.paymentStatus, totalAmount = bill.totalAmount,
            paidAmount = money.netCollected, remainingAmount = money.outstanding(bill.totalAmount),
        )
}

/** Thông tin phiên/bàn tối thiểu để dựng [BillResponse] (từ phiên đã khóa hoặc đọc không khóa). */
data class LockedSessionView(
    val sessionId: Long,
    val sessionStatus: SessionStatus,
    val tableId: Long,
    val tableCode: String,
) {
    companion object {
        fun of(locked: LockedSession) = LockedSessionView(locked.sessionId, locked.session.status, locked.tableId, locked.table.code)
    }
}

/** Kiểm tra version gửi kèm (tùy chọn) - khóa lạc quan cho thao tác của nhân viên. */
internal fun requireVersionIfGiven(requested: Long?, current: Long) {
    if (requested != null && requested != current) throw BusinessException(ErrorCode.CONCURRENT_MODIFICATION)
}

/**
 * Thông tin nhà hàng in trên phiếu thanh toán nội bộ (FR-PAY-12). SRS/DB chưa có bảng thông tin nhà hàng (DB-10: bổ sung
 * sau) nên đọc từ cấu hình `app.receipt.*` (biến môi trường RECEIPT_*), không hardcode.
 */
@ConfigurationProperties(prefix = "app.receipt")
data class ReceiptProperties(
    val restaurantName: String = "",
    val address: String = "",
    val phone: String = "",
    val taxCode: String = "",
    val footer: String = "Phiếu thanh toán nội bộ - không thay thế hóa đơn điện tử",
)
