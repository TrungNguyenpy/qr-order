package com.qrorder.table.service

import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.table.domain.SessionStatus

/** Danh mục audit action của module table (UPPER_SNAKE_CASE, CHECK ck_audit_log_action; DATABASE_DESIGN.md §6). */
object TableAuditActions {
    const val TABLE_CREATED = "TABLE_CREATED"
    const val TABLE_UPDATED = "TABLE_UPDATED"
    const val TABLE_ACTIVE_CHANGED = "TABLE_ACTIVE_CHANGED"
    const val TABLE_STATUS_CHANGED = "TABLE_STATUS_CHANGED"
    const val QR_GENERATED = "QR_GENERATED"
    const val QR_ROTATED = "QR_ROTATED"
    const val QR_REVOKED = "QR_REVOKED"
    /** Tải ảnh PNG/PDF chứa QR (lộ URL có token) - ghi lại ai đã xuất mã. */
    const val QR_EXPORTED = "QR_EXPORTED"
    const val SESSION_OPENED_BY_STAFF = "SESSION_OPENED_BY_STAFF"
    /** Bắt buộc lý do (CHECK ck_audit_log_reason_required). */
    const val SESSION_FORCE_CLOSED = "SESSION_FORCE_CLOSED"
    const val SESSION_EXPIRED = "SESSION_EXPIRED"
    const val SESSION_NEEDS_ATTENTION = "SESSION_NEEDS_ATTENTION"
}

/** audit_log.entity_type = tên bảng. */
object TableAuditEntityTypes {
    const val DINING_TABLE = "dining_table"
    const val QR_CODE = "qr_code"
    const val TABLE_SESSION = "table_session"
}

/** Phân quyền API module table (SRS §10). */
object TableAccess {
    /** CRUD bàn, QR, xoay QR, is_active: chỉ ADMIN. */
    const val ADMIN = "hasRole('ADMIN')"
    /** Xem bàn / sơ đồ bàn: ADMIN (quản trị) và nhân viên sảnh (STAFF, MANAGER). */
    const val VIEW_TABLES = "hasAnyRole('ADMIN','MANAGER','STAFF')"
    /** Xác nhận dọn bàn, mở phiên hộ khách, xem phiên: STAFF, MANAGER (ADMIN không kế thừa - BR-A06). */
    const val FLOOR = "hasAnyRole('MANAGER','STAFF')"
    /** Danh sách/chi tiết phiên: nhân viên sảnh, hoặc ADMIN có REPORT_VIEW (E2: danh sách Cần xử lý). */
    const val VIEW_SESSIONS = "hasAnyRole('MANAGER','STAFF') or @perm.has('REPORT_VIEW')"
    /** BR-S06: force-close cần SESSION_FORCE_CLOSE (MANAGER có mặc định). */
    const val FORCE_CLOSE = "@perm.has('SESSION_FORCE_CLOSE')"
    /** Khách theo phiên bàn (Guest JWT). */
    const val GUEST = "hasRole('GUEST')"

    const val MAX_PAGE_SIZE = 100
}

internal object TableGuards {
    val ACTIVE_SESSION_STATUSES: Set<SessionStatus> = setOf(SessionStatus.OPEN, SessionStatus.BILLING)

    fun requireVersion(requested: Long?, current: Long) {
        if (requested != current) throw BusinessException(ErrorCode.CONCURRENT_MODIFICATION)
    }

    fun optionalText(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    fun changedKeys(before: Map<String, Any?>, after: Map<String, Any?>): List<String> =
        before.keys.filter { before[it] != after[it] }
}
