package com.qrorder.billing.service

import com.qrorder.auth.domain.Permission
import com.qrorder.auth.security.PermissionGuard
import com.qrorder.auth.security.StaffPrincipal
import com.qrorder.billing.domain.BillStatus
import com.qrorder.billing.domain.VoidType
import com.qrorder.billing.dto.ReopenSessionRequest
import com.qrorder.billing.dto.ReopenSessionResponse
import com.qrorder.billing.event.BillChangeKind
import com.qrorder.billing.repository.BillRepository
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.common.time.DbClock
import com.qrorder.order.domain.Order
import com.qrorder.order.domain.OrderStatus
import com.qrorder.order.repository.OrderRepository
import com.qrorder.order.service.LockedSession
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.domain.TableStatus
import com.qrorder.table.event.SessionChangeKind
import com.qrorder.table.event.SessionChangedEvent
import com.qrorder.table.event.TableChangeKind
import com.qrorder.table.event.TableChangedEvent
import com.qrorder.table.repository.DiningTableRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Mở lại phiên BILLING (FR-PAY-06). Hai luồng:
 * - (a) GĐ5a - Staff (PAYMENT_PROCESS) hoặc Manager khi Bill hiện hành ACTIVE chưa có tiền thu (P1, BR-P08 a, SRS §6.16,
 *   §9.9 luồng 4) - mô tả bên dưới.
 * - (b) GĐ5b - không còn Bill hiện hành vì Manager đã Void (Order LOCKED chờ Bill thay thế): chỉ SESSION_REOPEN
 *   ([reopenAfterVoid]).
 * Controller cho phép PAYMENT_PROCESS hoặc SESSION_REOPEN; service kiểm tra đúng quyền của từng luồng.
 *
 * Điều kiện (sau khi khóa bàn -> phiên): phiên BILLING; Bill hiện hành ACTIVE (VOID_PENDING -> 409 BILL_VOID_PENDING);
 * Bill chưa có Payment SUCCESS (409 BILL_HAS_PAYMENTS - cần Manager Void, BR-P08 b / GĐ5b) và không có Payment PENDING
 * (409 PENDING_PAYMENT_EXISTS). Payment FAILED không chặn.
 *
 * Trong một transaction: Bill -> VOIDED (void_type SESSION_REOPEN, không có tiền cần hoàn), Order -> OPEN, phiên -> OPEN,
 * bàn -> OCCUPIED, audit SESSION_REOPENED (kèm lý do) và BILL_VOIDED. Sau khi chỉnh món, Staff Tính tiền lại để tạo Bill mới.
 */
@Service
class SessionReopenService(
    private val billingLocker: BillingLocker,
    private val permissionGuard: PermissionGuard,
    private val billRepository: BillRepository,
    private val orderRepository: OrderRepository,
    private val tableRepository: DiningTableRepository,
    private val mapper: BillViewMapper,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun reopen(actor: StaffPrincipal, sessionId: Long, request: ReopenSessionRequest): ReopenSessionResponse {
        val actorUserId = actor.userId
        val reason = requireNotNull(request.reason).trim()
        val locked = billingLocker.lockSession(sessionId)
        val session = locked.session
        if (session.status != SessionStatus.BILLING) {
            throw BusinessException(ErrorCode.SESSION_NOT_BILLING, "Phiên bàn đang ${session.status}, chỉ mở lại phiên đang tính tiền")
        }
        requireVersionIfGiven(request.version, session.version)
        val order = orderRepository.findBySessionId(sessionId) ?: throw ResourceNotFoundException("order của phiên", sessionId)
        val current = billRepository.findFirstByOrderIdAndStatusIn(requireNotNull(order.id), CURRENT_BILL_STATUSES)
        if (current == null) return reopenAfterVoid(actor, locked, order, reason)
        requirePermission(actor, Permission.PAYMENT_PROCESS, "Cần quyền PAYMENT_PROCESS để mở lại phiên khi Bill chưa có tiền thu (BR-P08 a)")
        val bill = current
        if (bill.status == BillStatus.VOID_PENDING) throw BusinessException(ErrorCode.BILL_VOID_PENDING)
        val money = mapper.money(requireNotNull(bill.id))
        if (money.tps > 0) {
            throw BusinessException(ErrorCode.BILL_HAS_PAYMENTS, "Bill ${bill.billNumber} đã có tiền thu, chỉ Manager mới xử lý được (Void có hoàn tiền - BR-P08 b)")
        }
        if (money.tpp > 0) {
            throw BusinessException(ErrorCode.PENDING_PAYMENT_EXISTS, "Bill ${bill.billNumber} còn giao dịch đang chờ, hãy xác nhận SUCCESS/FAILED trước")
        }

        val now = dbClock.now()
        bill.status = BillStatus.VOIDED
        bill.voidType = VoidType.SESSION_REOPEN
        bill.voidReason = reason
        bill.voidedBy = actorUserId
        bill.voidedAt = now
        billRepository.saveAndFlush(bill)

        val previousOrderStatus = order.status
        if (order.status == OrderStatus.PAID) {
            // Bill Total = 0 đã PAID mà chưa có tiền thu: trigger chỉ cho PAID -> LOCKED -> OPEN.
            order.status = OrderStatus.LOCKED
            orderRepository.saveAndFlush(order)
        }
        order.status = OrderStatus.OPEN
        order.paidAt = null
        orderRepository.saveAndFlush(order)

        session.status = SessionStatus.OPEN
        session.billingStartedAt = null
        session.lastActivityAt = now
        val savedSession = billingLocker.saveSession(locked)
        val previousTableStatus = locked.table.status
        locked.table.status = TableStatus.OCCUPIED
        locked.table.statusChangedAt = now
        val table = tableRepository.saveAndFlush(locked.table)

        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = BillingAuditActions.SESSION_REOPENED, entityType = BillingAuditEntityTypes.TABLE_SESSION, entityId = sessionId,
                oldValue = mapOf("status" to SessionStatus.BILLING, "orderStatus" to previousOrderStatus, "tableStatus" to previousTableStatus, "billId" to bill.id),
                newValue = mapOf("status" to SessionStatus.OPEN, "orderStatus" to OrderStatus.OPEN, "tableStatus" to TableStatus.OCCUPIED, "billStatus" to BillStatus.VOIDED),
                reason = reason,
            ),
        )
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = BillingAuditActions.BILL_VOIDED, entityType = BillingAuditEntityTypes.BILL, entityId = bill.id,
                oldValue = mapOf("status" to BillStatus.ACTIVE), newValue = mapOf("status" to BillStatus.VOIDED, "voidType" to VoidType.SESSION_REOPEN),
                reason = reason,
            ),
        )
        events.publishEvent(mapper.event(BillChangeKind.BILL_VOIDED, locked, bill, money))
        events.publishEvent(SessionChangedEvent(sessionId, locked.tableId, table.code, savedSession.status, savedSession.version, SessionChangeKind.REOPENED))
        events.publishEvent(TableChangedEvent(locked.tableId, table.code, table.status, table.active, table.version, TableChangeKind.STATUS_CHANGED, sessionId))

        return ReopenSessionResponse(
            sessionId = sessionId, sessionStatus = savedSession.status, tableId = locked.tableId, tableStatus = table.status,
            orderId = requireNotNull(order.id), orderStatus = order.status, voidedBillId = requireNotNull(bill.id),
            voidedBillNumber = requireNotNull(bill.billNumber), sessionVersion = savedSession.version,
        )
    }

    /**
     * BR-P08 (b), FR-PAY-06 (GĐ5b): phiên BILLING mà Bill đã VOIDED do Manager Void (hoàn tiền xong hoặc không có tiền
     * cần hoàn) và Order LOCKED chờ Bill thay thế - chỉ Manager / người có SESSION_REOPEN mở lại: Order OPEN (xóa
     * needs_attention), phiên OPEN, bàn OCCUPIED, audit SESSION_REOPENED. Không đổi Bill (đã VOIDED).
     */
    private fun reopenAfterVoid(actor: StaffPrincipal, locked: LockedSession, order: Order, reason: String): ReopenSessionResponse {
        val orderId = requireNotNull(order.id)
        val noBill = BusinessException(ErrorCode.BILL_NOT_ACTIVE, "Phiên không có Bill hiện hành để mở lại")
        val latest = billRepository.findAllByOrderIdOrderByIdAsc(orderId).lastOrNull() ?: throw noBill
        val awaitingReplacement = order.status == OrderStatus.LOCKED && order.needsAttention &&
            latest.status == BillStatus.VOIDED && latest.voidType == VoidType.MANAGER_VOID
        if (!awaitingReplacement) throw noBill
        requirePermission(actor, Permission.SESSION_REOPEN, "Bill đã Void, chỉ Manager (SESSION_REOPEN) được mở lại phiên (BR-P08 b)")
        val now = dbClock.now()
        order.needsAttention = false
        order.attentionReason = null
        order.attentionSetAt = null
        order.status = OrderStatus.OPEN
        order.paidAt = null
        orderRepository.saveAndFlush(order)

        val session = locked.session
        session.status = SessionStatus.OPEN
        session.billingStartedAt = null
        session.lastActivityAt = now
        val savedSession = billingLocker.saveSession(locked)
        val previousTableStatus = locked.table.status
        locked.table.status = TableStatus.OCCUPIED
        locked.table.statusChangedAt = now
        val table = tableRepository.saveAndFlush(locked.table)

        auditService.record(
            AuditActor.User(actor.userId),
            AuditEvent(
                action = BillingAuditActions.SESSION_REOPENED, entityType = BillingAuditEntityTypes.TABLE_SESSION, entityId = locked.sessionId,
                oldValue = mapOf(
                    "status" to SessionStatus.BILLING, "orderStatus" to OrderStatus.LOCKED, "tableStatus" to previousTableStatus,
                    "voidedBillId" to latest.id, "orderNeedsAttention" to true,
                ),
                newValue = mapOf("status" to SessionStatus.OPEN, "orderStatus" to OrderStatus.OPEN, "tableStatus" to TableStatus.OCCUPIED),
                reason = reason,
            ),
        )
        events.publishEvent(SessionChangedEvent(locked.sessionId, locked.tableId, table.code, savedSession.status, savedSession.version, SessionChangeKind.REOPENED))
        events.publishEvent(TableChangedEvent(locked.tableId, table.code, table.status, table.active, table.version, TableChangeKind.STATUS_CHANGED, locked.sessionId))
        return ReopenSessionResponse(
            sessionId = locked.sessionId, sessionStatus = savedSession.status, tableId = locked.tableId, tableStatus = table.status,
            orderId = orderId, orderStatus = order.status, voidedBillId = requireNotNull(latest.id),
            voidedBillNumber = requireNotNull(latest.billNumber), sessionVersion = savedSession.version,
        )
    }

    private fun requirePermission(actor: StaffPrincipal, permission: Permission, message: String) {
        if (!permissionGuard.hasPermission(actor, permission)) throw BusinessException(ErrorCode.PERMISSION_DENIED, message)
    }
}
