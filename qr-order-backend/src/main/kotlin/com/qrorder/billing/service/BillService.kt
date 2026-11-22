package com.qrorder.billing.service

import com.qrorder.auth.domain.Permission
import com.qrorder.auth.security.PermissionGuard
import com.qrorder.auth.security.StaffPrincipal
import com.qrorder.billing.domain.Bill
import com.qrorder.billing.domain.BillAmounts
import com.qrorder.billing.domain.BillCalculator
import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.domain.DiscountInput
import com.qrorder.billing.domain.DiscountType
import com.qrorder.billing.domain.PaymentStatus
import com.qrorder.billing.domain.VoidType
import com.qrorder.billing.dto.BillResponse
import com.qrorder.billing.dto.CreateBillRequest
import com.qrorder.billing.dto.GuestBillResponse
import com.qrorder.billing.dto.UpdateBillDiscountRequest
import com.qrorder.billing.event.BillChangeKind
import com.qrorder.billing.repository.BillRepository
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.common.settings.RestaurantSettingsService
import com.qrorder.common.time.DbClock
import com.qrorder.notification.domain.ServiceCallType
import com.qrorder.notification.servicecall.ServiceCallService
import com.qrorder.order.domain.BatchStatus
import com.qrorder.order.domain.Order
import com.qrorder.order.domain.OrderItem
import com.qrorder.order.domain.OrderStatus
import com.qrorder.order.repository.OrderBatchRepository
import com.qrorder.order.repository.OrderItemRepository
import com.qrorder.order.repository.OrderRepository
import com.qrorder.order.service.LockedSession
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.domain.TableStatus
import com.qrorder.table.event.SessionChangeKind
import com.qrorder.table.event.SessionChangedEvent
import com.qrorder.table.event.TableChangeKind
import com.qrorder.table.event.TableChangedEvent
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.TableSessionRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * Tính tiền và giảm giá (FR-PAY-01..03, FR-PAY-05, BR-P01..P07, BR-O11, DEC-08 B, O5).
 *
 * Tạo Bill (nhân viên PAYMENT_PROCESS hoặc Manager), trong MỘT transaction sau khi khóa bàn -> phiên:
 * 1. Order phải OPEN, phiên OPEN; vat_rate đã cấu hình (422 VAT_NOT_CONFIGURED); không còn đợt PENDING kể cả đang
 *    được claim (409 BATCH_PENDING); có ít nhất một món tính tiền (409 NO_BILLABLE_ITEMS).
 * 2. Bill ACTIVE với snapshot Subtotal/Discount/Net/ServiceFee/Total/VAT ([BillCalculator]); mã BILL-YYYYMMDD-NNNN do
 *    trigger cấp. Order OPEN -> LOCKED, phiên OPEN -> BILLING, bàn -> PAYMENT_PENDING (BR-P05).
 * 3. ServiceCall REQUEST_PAYMENT đang mở của phiên tự RESOLVED (DEC-08 B).
 * 4. Total = 0 (mọi món giá 0 hoặc giảm 100%): Bill PAID ngay (bất biến BR-P11 ở DB), Order PAID, đóng phiên nếu món đã xong.
 *
 * Giảm giá chỉ nhập khi tạo Bill (BR-P07) bởi người có BILL_DISCOUNT. Đổi giảm giá sau đó = phát hành lại Bill (BE-29):
 * Bill cũ VOIDED (MANAGER_VOID, không có tiền cần hoàn) + Bill mới thay thế (replaces_bill_id) trong cùng transaction,
 * chỉ khi Bill chưa có Payment PENDING/SUCCESS - vì số liệu Bill là snapshot bất biến (BR-P06, trigger fn_bill_guard).
 */
@Service
class BillService(
    private val billingLocker: BillingLocker,
    private val billRepository: BillRepository,
    private val orderRepository: OrderRepository,
    private val batchRepository: OrderBatchRepository,
    private val itemRepository: OrderItemRepository,
    private val sessionRepository: TableSessionRepository,
    private val tableRepository: DiningTableRepository,
    private val settingsService: RestaurantSettingsService,
    private val permissionGuard: PermissionGuard,
    private val serviceCallService: ServiceCallService,
    private val settlement: SessionSettlementService,
    private val mapper: BillViewMapper,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
) {
    // =========================================================================================== Tạo Bill

    @Transactional
    fun create(actor: StaffPrincipal, orderId: Long, request: CreateBillRequest): BillResponse {
        val discount = request.discount?.takeIf { it.type != DiscountType.NONE }
        if (discount != null) requireDiscountPermission(actor)

        val (locked, order) = billingLocker.lockOrder(orderId)
        val current = billRepository.findFirstByOrderIdAndStatusIn(orderId, CURRENT_BILL_STATUSES)
        if (current != null) throw BusinessException(ErrorCode.BILL_ALREADY_ACTIVE, "Order đã có Bill ${current.billNumber}")
        if (order.status != OrderStatus.OPEN) {
            throw BusinessException(ErrorCode.ORDER_NOT_OPEN, "Order đang ${order.status}, không thể tính tiền")
        }
        if (locked.session.status != SessionStatus.OPEN) throw BusinessException(ErrorCode.SESSION_NOT_OPEN, "Phiên bàn đang ${locked.session.status}")

        val settings = settingsService.current()
        val vatRate = settings.vatRate ?: throw BusinessException(ErrorCode.VAT_NOT_CONFIGURED)
        if (batchRepository.countBySessionIdAndStatus(locked.sessionId, BatchStatus.PENDING) > 0) {
            throw BusinessException(ErrorCode.BATCH_PENDING)
        }
        val items = itemRepository.findAllByOrderIdOrderByIdAsc(orderId)
        if (items.none { it.status.isBillable }) throw BusinessException(ErrorCode.NO_BILLABLE_ITEMS)

        val now = dbClock.now()
        val subtotal = billableSubtotal(items)
        val discountAmount = BillCalculator.discountAmount(subtotal, discount?.let { DiscountInput(requireNotNull(it.type), it.value) })
        val amounts = BillCalculator.calculate(subtotal, discountAmount, settings.serviceFeeRate, settings.serviceFeeTaxable, vatRate)
        val discountReason = discount?.reason?.trim()

        // Order OPEN -> LOCKED trước (trigger không cho OPEN -> PAID trực tiếp).
        order.status = OrderStatus.LOCKED
        orderRepository.saveAndFlush(order)
        val bill = insertBill(order, amounts, actor.userId, discountReason, replaces = null, now)

        locked.session.status = SessionStatus.BILLING
        locked.session.billingStartedAt = now
        locked.session.lastActivityAt = now
        billingLocker.saveSession(locked)
        val previousTableStatus = locked.table.status
        locked.table.status = TableStatus.PAYMENT_PENDING
        locked.table.statusChangedAt = now
        tableRepository.saveAndFlush(locked.table)

        val resolved = serviceCallService.autoResolve(
            locked.sessionId, locked.tableId, locked.table.code, listOf(ServiceCallType.REQUEST_PAYMENT),
            "Nhân viên đã tính tiền (${bill.billNumber})",
        )
        auditService.record(
            AuditActor.User(actor.userId),
            AuditEvent(
                action = BillingAuditActions.BILL_CREATED, entityType = BillingAuditEntityTypes.BILL, entityId = bill.id,
                oldValue = mapOf("orderStatus" to OrderStatus.OPEN, "sessionStatus" to SessionStatus.OPEN, "tableStatus" to previousTableStatus),
                newValue = snapshot(bill) + mapOf("resolvedServiceCalls" to resolved.map { it.id }),
            ),
        )
        if (amounts.discount > 0) auditDiscount(actor.userId, bill, discount?.type, discount?.value, requireNotNull(discountReason), previous = null)

        publishBillingStarted(locked)
        events.publishEvent(mapper.event(BillChangeKind.BILL_CREATED, locked, bill))
        if (bill.paymentStatus == PaymentStatus.PAID) markOrderPaid(locked, order, bill, actor.userId, now)
        return mapper.bill(bill, order, LockedSessionView.of(locked))
    }

    // =========================================================================================== Giảm giá (BE-29)

    /**
     * Đổi giảm giá của Bill ACTIVE chưa có Payment PENDING/SUCCESS bằng cách phát hành lại Bill (BR-P06, BR-P07, BE-29).
     * Cần BILL_DISCOUNT (kiểm tra ở controller). [request] type NONE = gỡ giảm giá.
     */
    @Transactional
    fun updateDiscount(actor: StaffPrincipal, billId: Long, request: UpdateBillDiscountRequest): BillResponse {
        val ctx = billingLocker.lockBill(billId)
        val old = ctx.bill
        if (old.status != BillStatus.ACTIVE) throw BusinessException(ErrorCode.BILL_NOT_ACTIVE, "Bill ${old.billNumber} đang ${old.status}")
        requireVersionIfGiven(request.version, old.version)
        val money = mapper.money(billId)
        if (money.tps > 0 || money.tpp > 0) {
            throw BusinessException(ErrorCode.BILL_HAS_PAYMENTS, "Bill đã có giao dịch thanh toán, không thể đổi giảm giá (cần Manager Void - BR-P09)")
        }
        if (ctx.order.status != OrderStatus.LOCKED) {
            throw BusinessException(ErrorCode.INVALID_ORDER_TRANSITION, "Order đang ${ctx.order.status}, không thể phát hành lại Bill")
        }
        val settings = settingsService.current()
        val vatRate = settings.vatRate ?: throw BusinessException(ErrorCode.VAT_NOT_CONFIGURED)
        val items = itemRepository.findAllByOrderIdOrderByIdAsc(ctx.orderId)
        val subtotal = billableSubtotal(items)
        val type = requireNotNull(request.type)
        val discountAmount = BillCalculator.discountAmount(subtotal, DiscountInput(type, request.value))
        if (discountAmount == old.discountAmount && subtotal == old.subtotalAmount) {
            throw BusinessException(ErrorCode.INVALID_DISCOUNT, "Giảm giá không thay đổi so với Bill hiện tại")
        }
        val reason = requireNotNull(request.reason).trim()
        val amounts = BillCalculator.calculate(subtotal, discountAmount, settings.serviceFeeRate, settings.serviceFeeTaxable, vatRate)
        val now = dbClock.now()

        // Bill cũ VOIDED trước (giải phóng ux_bill_current_per_order) rồi mới chèn Bill thay thế.
        old.status = BillStatus.VOIDED
        old.voidType = VoidType.MANAGER_VOID
        old.voidRequestedBy = actor.userId
        old.voidRequestedAt = now
        old.voidReason = "Điều chỉnh giảm giá: $reason"
        old.voidedBy = actor.userId
        old.voidedAt = now
        billRepository.saveAndFlush(old)
        val bill = insertBill(ctx.order, amounts, actor.userId, if (discountAmount > 0) reason else null, replaces = old, now)

        auditService.record(
            AuditActor.User(actor.userId),
            AuditEvent(
                action = BillingAuditActions.BILL_VOIDED, entityType = BillingAuditEntityTypes.BILL, entityId = old.id,
                oldValue = mapOf("status" to BillStatus.ACTIVE), newValue = mapOf("status" to BillStatus.VOIDED, "voidType" to VoidType.MANAGER_VOID, "replacedBy" to bill.id),
                reason = old.voidReason,
            ),
        )
        auditDiscount(actor.userId, bill, type, request.value, reason, previous = old)
        events.publishEvent(mapper.event(BillChangeKind.BILL_REISSUED, ctx.locked, bill))
        if (bill.paymentStatus == PaymentStatus.PAID) markOrderPaid(ctx.locked, ctx.order, bill, actor.userId, now)
        return mapper.bill(bill, ctx.order, LockedSessionView.of(ctx.locked))
    }

    // =========================================================================================== Bill thay thế (GĐ5b)

    /**
     * Manager (BILL_VOID - kiểm tra ở controller) tạo Bill thay thế sau Void (BR-P16, O4, P-3, SRS §6.14):
     * Order LOCKED chờ Bill thay thế, không có Bill hiện hành, Bill gần nhất VOIDED (nếu từng có tiền thu thì Refund đã
     * hoàn đủ - trigger bảo đảm), VAT hợp lệ, không còn đợt PENDING, có món tính tiền. Bill mới ACTIVE (`replaces_bill_id`
     * = Bill gần nhất), số liệu tính lại từ món hiện tại, giảm giá tùy chọn (cần BILL_DISCOUNT, BR-P07); xóa needs_attention
     * của Order; không chuyển tiền từ Bill cũ. Phiên và bàn giữ nguyên (BILLING hoặc đã CLOSED).
     */
    @Transactional
    fun createReplacement(actor: StaffPrincipal, orderId: Long, request: CreateBillRequest): BillResponse {
        val discount = request.discount?.takeIf { it.type != DiscountType.NONE }
        if (discount != null) requireDiscountPermission(actor)

        val (locked, order) = billingLocker.lockOrder(orderId)
        billRepository.findFirstByOrderIdAndStatusIn(orderId, CURRENT_BILL_STATUSES)?.let {
            if (it.status == BillStatus.VOID_PENDING) throw BusinessException(ErrorCode.BILL_VOID_PENDING, "Bill ${it.billNumber} chưa hoàn tiền xong")
            throw BusinessException(ErrorCode.BILL_ALREADY_ACTIVE, "Order đã có Bill ${it.billNumber}")
        }
        val latest = billRepository.findAllByOrderIdOrderByIdAsc(orderId).lastOrNull()
        if (order.status != OrderStatus.LOCKED || latest == null || latest.status != BillStatus.VOIDED) {
            throw BusinessException(ErrorCode.REPLACEMENT_BILL_NOT_ALLOWED, "Order đang ${order.status}, Bill gần nhất ${latest?.status ?: "không có"}")
        }
        val settings = settingsService.current()
        val vatRate = settings.vatRate ?: throw BusinessException(ErrorCode.VAT_NOT_CONFIGURED)
        if (batchRepository.countBySessionIdAndStatus(locked.sessionId, BatchStatus.PENDING) > 0) {
            throw BusinessException(ErrorCode.BATCH_PENDING)
        }
        val items = itemRepository.findAllByOrderIdOrderByIdAsc(orderId)
        if (items.none { it.status.isBillable }) throw BusinessException(ErrorCode.NO_BILLABLE_ITEMS)

        val now = dbClock.now()
        val subtotal = billableSubtotal(items)
        val discountAmount = BillCalculator.discountAmount(subtotal, discount?.let { DiscountInput(requireNotNull(it.type), it.value) })
        val amounts = BillCalculator.calculate(subtotal, discountAmount, settings.serviceFeeRate, settings.serviceFeeTaxable, vatRate)
        val discountReason = discount?.reason?.trim()
        val bill = insertBill(order, amounts, actor.userId, discountReason, replaces = latest, now)

        order.needsAttention = false
        order.attentionReason = null
        order.attentionSetAt = null
        orderRepository.saveAndFlush(order)

        auditService.record(
            AuditActor.User(actor.userId),
            AuditEvent(
                action = BillingAuditActions.BILL_REPLACEMENT_CREATED, entityType = BillingAuditEntityTypes.BILL, entityId = bill.id,
                oldValue = mapOf("replacesBillId" to latest.id, "replacesBillNumber" to latest.billNumber, "orderNeedsAttention" to true),
                newValue = snapshot(bill) + mapOf("orderNeedsAttention" to false),
            ),
        )
        if (amounts.discount > 0) auditDiscount(actor.userId, bill, discount?.type, discount?.value, requireNotNull(discountReason), previous = null)
        events.publishEvent(mapper.event(BillChangeKind.REPLACEMENT_CREATED, locked, bill))
        if (bill.paymentStatus == PaymentStatus.PAID) markOrderPaid(locked, order, bill, actor.userId, now)
        return mapper.bill(bill, order, LockedSessionView.of(locked))
    }

    // =========================================================================================== Đọc

    @Transactional(readOnly = true)
    fun byId(billId: Long): BillResponse {
        val bill = billRepository.findById(billId).orElseThrow { ResourceNotFoundException("Bill", billId) }
        return view(bill)
    }

    /** Bill hiện hành (ACTIVE/VOID_PENDING) của Order, hoặc Bill gần nhất nếu không còn Bill hiện hành. */
    @Transactional(readOnly = true)
    fun latestByOrder(orderId: Long): BillResponse {
        val bill = billRepository.findFirstByOrderIdAndStatusIn(orderId, CURRENT_BILL_STATUSES)
            ?: billRepository.findAllByOrderIdOrderByIdAsc(orderId).lastOrNull()
            ?: throw ResourceNotFoundException("Bill của order", orderId)
        return view(bill)
    }

    @Transactional(readOnly = true)
    fun historyByOrder(orderId: Long): List<BillResponse> {
        if (!orderRepository.existsById(orderId)) throw ResourceNotFoundException("order", orderId)
        return billRepository.findAllByOrderIdOrderByIdAsc(orderId).map(::view)
    }

    /** Phiếu tạm tính của khách (UC10): Bill gần nhất chưa VOIDED của phiên. */
    @Transactional(readOnly = true)
    fun guestBill(sessionId: Long): GuestBillResponse {
        val order = orderRepository.findBySessionId(sessionId) ?: throw ResourceNotFoundException("Bill của phiên", null)
        val bill = billRepository.findAllByOrderIdOrderByIdAsc(requireNotNull(order.id)).lastOrNull { it.status != BillStatus.VOIDED }
            ?: throw ResourceNotFoundException("Bill của phiên", null)
        return mapper.guestBill(bill)
    }

    // =========================================================================================== nội bộ

    private fun view(bill: Bill): BillResponse {
        val order = orderRepository.findById(bill.orderId).orElseThrow { ResourceNotFoundException("order", bill.orderId) }
        val session = sessionRepository.findById(order.sessionId).orElseThrow { ResourceNotFoundException("phiên bàn", order.sessionId) }
        val table = tableRepository.findById(session.tableId).orElseThrow { ResourceNotFoundException("bàn", session.tableId) }
        return mapper.bill(bill, order, LockedSessionView(requireNotNull(session.id), session.status, requireNotNull(table.id), table.code))
    }

    private fun requireDiscountPermission(actor: StaffPrincipal) {
        if (!permissionGuard.hasPermission(actor, Permission.BILL_DISCOUNT)) {
            throw BusinessException(ErrorCode.PERMISSION_DENIED, "Cần quyền BILL_DISCOUNT để nhập giảm giá (BR-P07)")
        }
    }

    private fun billableSubtotal(items: List<OrderItem>): Long = items.filter { it.status.isBillable }.sumOf { it.lineTotal }

    /** Chèn Bill ACTIVE; Total = 0 thì PAID ngay trong cùng transaction (trg_bill_invariants kiểm tra lúc COMMIT). */
    private fun insertBill(order: Order, a: BillAmounts, actorUserId: Long, discountReason: String?, replaces: Bill?, now: OffsetDateTime): Bill {
        val bill = Bill(
            orderId = requireNotNull(order.id), subtotalAmount = a.subtotal, discountAmount = a.discount, netAmount = a.net,
            serviceFeeRate = a.serviceFeeRate, serviceFeeAmount = a.serviceFee, totalAmount = a.total, vatRate = a.vatRate,
            vatAmount = a.vatAmount, createdBy = actorUserId,
        ).apply {
            serviceFeeTaxable = a.serviceFeeTaxable
            if (a.discount > 0) {
                this.discountReason = discountReason
                discountedBy = actorUserId
            }
            replacesBillId = replaces?.id
            if (a.total == 0L) {
                paymentStatus = PaymentStatus.PAID
                paidAt = now
            }
        }
        return billRepository.saveAndFlush(bill)
    }

    /** Bill PAID (Total = 0): Order LOCKED -> PAID, rồi tự đóng phiên nếu món đã xong. */
    private fun markOrderPaid(locked: LockedSession, order: Order, bill: Bill, actorUserId: Long, now: OffsetDateTime) {
        order.status = OrderStatus.PAID
        order.paidAt = now
        orderRepository.saveAndFlush(order)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = BillingAuditActions.BILL_PAID, entityType = BillingAuditEntityTypes.BILL, entityId = bill.id,
                newValue = mapOf("paymentStatus" to PaymentStatus.PAID, "totalAmount" to bill.totalAmount, "orderStatus" to OrderStatus.PAID),
            ),
        )
        events.publishEvent(mapper.event(BillChangeKind.BILL_PAID, locked, bill))
        settlement.closeIfSettled(locked, order, actorUserId)
    }

    private fun publishBillingStarted(locked: LockedSession) {
        val s = locked.session
        events.publishEvent(SessionChangedEvent(locked.sessionId, locked.tableId, locked.table.code, s.status, s.version, SessionChangeKind.BILLING_STARTED))
        events.publishEvent(
            TableChangedEvent(locked.tableId, locked.table.code, locked.table.status, locked.table.active, locked.table.version, TableChangeKind.STATUS_CHANGED, locked.sessionId),
        )
    }

    private fun auditDiscount(actorUserId: Long, bill: Bill, type: DiscountType?, value: BigDecimal?, reason: String, previous: Bill?) {
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = BillingAuditActions.BILL_DISCOUNT_APPLIED, entityType = BillingAuditEntityTypes.BILL, entityId = bill.id,
                oldValue = previous?.let { mapOf("billId" to it.id, "billNumber" to it.billNumber, "discountAmount" to it.discountAmount, "totalAmount" to it.totalAmount) },
                newValue = mapOf(
                    "discountType" to type, "discountValue" to value, "discountAmount" to bill.discountAmount,
                    "netAmount" to bill.netAmount, "totalAmount" to bill.totalAmount, "billNumber" to bill.billNumber,
                ),
                reason = reason,
            ),
        )
    }

    private fun snapshot(b: Bill): Map<String, Any?> = mapOf(
        "billNumber" to b.billNumber, "orderId" to b.orderId, "subtotalAmount" to b.subtotalAmount, "discountAmount" to b.discountAmount,
        "netAmount" to b.netAmount, "serviceFeeRate" to b.serviceFeeRate, "serviceFeeAmount" to b.serviceFeeAmount,
        "totalAmount" to b.totalAmount, "vatRate" to b.vatRate, "vatAmount" to b.vatAmount, "paymentStatus" to b.paymentStatus,
    )
}
