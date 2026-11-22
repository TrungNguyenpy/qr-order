package com.qrorder.billing.service

import com.qrorder.auth.repository.UserRepository
import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.domain.PaymentTransaction
import com.qrorder.billing.domain.TransactionStatus
import com.qrorder.billing.dto.PaymentReceiptResponse
import com.qrorder.billing.dto.ReceiptHeader
import com.qrorder.billing.repository.BillRepository
import com.qrorder.billing.repository.PaymentTransactionRepository
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.order.repository.OrderRepository
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.TableSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Dữ liệu in phiếu thanh toán nội bộ (FR-PAY-12, BR-P12, DEC-11 C) cho từng giao dịch SUCCESS. Backend trả JSON đủ để
 * Staff App/Admin dựng bản in (máy in nhiệt hoặc PDF phía client); đây không phải hóa đơn điện tử.
 * Phiếu hoàn tiền RF-... thuộc GĐ5b (Void có hoàn tiền).
 */
@Service
class ReceiptService(
    private val paymentRepository: PaymentTransactionRepository,
    private val billRepository: BillRepository,
    private val orderRepository: OrderRepository,
    private val sessionRepository: TableSessionRepository,
    private val tableRepository: DiningTableRepository,
    private val userRepository: UserRepository,
    private val mapper: BillViewMapper,
    private val properties: ReceiptProperties,
) {
    @Transactional(readOnly = true)
    fun paymentReceipt(paymentId: Long): PaymentReceiptResponse {
        val payment = paymentRepository.findById(paymentId).orElseThrow { ResourceNotFoundException("giao dịch thanh toán", paymentId) }
        if (payment.status != TransactionStatus.SUCCESS) {
            throw BusinessException(ErrorCode.RECEIPT_NOT_AVAILABLE, "Giao dịch đang ${payment.status}, chưa có phiếu thanh toán")
        }
        val bill = billRepository.findById(payment.billId).orElseThrow { ResourceNotFoundException("Bill", payment.billId) }
        val order = orderRepository.findById(bill.orderId).orElseThrow { ResourceNotFoundException("order", bill.orderId) }
        val session = sessionRepository.findById(order.sessionId).orElseThrow { ResourceNotFoundException("phiên bàn", order.sessionId) }
        val table = tableRepository.findById(session.tableId).orElseThrow { ResourceNotFoundException("bàn", session.tableId) }

        // Tiền đã thu trước giao dịch này: các giao dịch SUCCESS xác nhận trước (thời điểm xác nhận, rồi id).
        val byConfirmation = compareBy<PaymentTransaction>({ it.confirmedAt ?: OffsetDateTime.MAX }, { it.id })
        val successes = paymentRepository.findAllByBillIdOrderByIdAsc(requireNotNull(bill.id))
            .filter { it.status == TransactionStatus.SUCCESS }
            .sortedWith(byConfirmation)
        val paidBefore = successes.takeWhile { it.id != payment.id }.sumOf { it.amount }
        val paidAfter = paidBefore + payment.amount
        val cashierId = payment.confirmedBy ?: payment.createdBy

        return PaymentReceiptResponse(
            header = ReceiptHeader(
                restaurantName = properties.restaurantName,
                address = properties.address.takeIf { it.isNotBlank() },
                phone = properties.phone.takeIf { it.isNotBlank() },
                taxCode = properties.taxCode.takeIf { it.isNotBlank() },
            ),
            receiptNumber = requireNotNull(payment.receiptNumber),
            issuedAt = payment.confirmedAt,
            billNumber = requireNotNull(bill.billNumber),
            billStatus = bill.status,
            tableCode = table.code,
            tableName = table.displayName,
            sessionOpenedAt = session.openedAt,
            cashierName = userRepository.findById(cashierId).map { it.fullName }.orElse(null),
            method = payment.method,
            amount = payment.amount,
            provider = payment.provider,
            providerTxnId = payment.providerTxnId,
            lines = if (bill.status == BillStatus.VOIDED) emptyList() else mapper.lines(bill.orderId),
            subtotalAmount = bill.subtotalAmount,
            discountAmount = bill.discountAmount,
            discountReason = bill.discountReason,
            netAmount = bill.netAmount,
            serviceFeeRate = bill.serviceFeeRate,
            serviceFeeAmount = bill.serviceFeeAmount,
            totalAmount = bill.totalAmount,
            vatRate = bill.vatRate,
            vatAmount = bill.vatAmount,
            paidBefore = paidBefore,
            paidAfter = paidAfter,
            remainingAfter = maxOf(0L, bill.totalAmount - paidAfter),
            footer = properties.footer,
        )
    }
}
