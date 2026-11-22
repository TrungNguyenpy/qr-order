package com.qrorder.common.domain

/**
 * Người khởi tạo một bản ghi do khách hoặc nhân viên tạo:
 * table_session.opened_by_type, order_batch.submitted_by_type, service_call.created_by_type,
 * item_cancel_request.requested_by_type (CHECK: GUEST | STAFF).
 */
enum class RequesterType { GUEST, STAFF }

/**
 * Ai thực hiện một lần đổi trạng thái món: order_item.status_changed_by_type,
 * order_item_status_history.changed_by_type (CHECK: GUEST | STAFF | SYSTEM, BR-O09).
 */
enum class StatusActorType { GUEST, STAFF, SYSTEM }

/** Người thực hiện thao tác ghi audit: audit_log.actor_type (CHECK: USER | GUEST | SYSTEM). */
enum class AuditActorType { USER, GUEST, SYSTEM }
