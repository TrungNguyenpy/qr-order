package com.qrorder.notification.servicecall

import com.qrorder.notification.domain.ServiceCallType
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.TableSessionRepository
import com.qrorder.table.session.SessionExpiryListener
import com.qrorder.table.session.SessionForceCloseContext
import com.qrorder.table.session.SessionForceCloseListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Phiên kết thúc bằng force-close hoặc EXPIRED: tự RESOLVED mọi ServiceCall còn mở của phiên (GĐ5a) để hàng đợi
 * "Cần xử lý" không còn yêu cầu của phiên đã đóng. (Phiên CLOSED do thanh toán xong được xử lý trong
 * SessionSettlementService.) Chạy trong cùng transaction, sau khi bàn + phiên đã khóa.
 */
@Component
@Order(50)
class ServiceCallSessionHooks(
    private val serviceCallService: ServiceCallService,
    private val sessionRepository: TableSessionRepository,
    private val tableRepository: DiningTableRepository,
) : SessionForceCloseListener, SessionExpiryListener {

    override fun onForceClose(context: SessionForceCloseContext) {
        val code = tableRepository.findById(context.tableId).map { it.code }.orElse("")
        serviceCallService.autoResolve(context.sessionId, context.tableId, code, ServiceCallType.entries, "Phiên bị đóng bởi Manager")
    }

    override fun onExpire(sessionId: Long) {
        val tableId = sessionRepository.findTableIdById(sessionId) ?: return
        val code = tableRepository.findById(tableId).map { it.code }.orElse("")
        serviceCallService.autoResolve(sessionId, tableId, code, ServiceCallType.entries, "Phiên hết hạn")
    }
}
