package com.qrorder.table.service

import com.qrorder.common.api.ErrorDetail
import com.qrorder.common.audit.AuditActor
import com.qrorder.common.audit.AuditEvent
import com.qrorder.common.audit.AuditService
import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.common.error.ResourceNotFoundException
import com.qrorder.common.time.DbClock
import com.qrorder.table.config.QrProperties
import com.qrorder.table.domain.DiningTable
import com.qrorder.table.domain.QrCode
import com.qrorder.table.domain.QrCodeStatus
import com.qrorder.table.dto.QrCodeResponse
import com.qrorder.table.dto.RevokeQrRequest
import com.qrorder.table.dto.RotateQrRequest
import com.qrorder.table.qr.QrImageRenderer
import com.qrorder.table.qr.QrPdfRenderer
import com.qrorder.table.qr.QrPrintPage
import com.qrorder.table.qr.QrTokenCodec
import com.qrorder.table.repository.DiningTableRepository
import com.qrorder.table.repository.QrCodeRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Nội dung file xuất (ảnh PNG / PDF) kèm tên file gợi ý. */
class QrExport(val fileName: String, val bytes: ByteArray)

/**
 * Mã QR bàn (FR-QR-01, FR-QR-02, BR-S05). Chỉ ADMIN (SRS §10).
 *
 * - Sinh: qrToken 256 bit dẫn xuất bằng HMAC từ id của bản ghi ([QrTokenCodec]), DB chỉ lưu SHA-256 hex.
 *   Tối đa 1 QR ACTIVE mỗi bàn: khóa dòng bàn rồi kiểm tra; partial unique index ux_qr_code_active_per_table là hàng
 *   rào cuối (409 QR_ALREADY_ACTIVE).
 * - Xoay: QR ACTIVE hiện tại -> ROTATED (token cũ vô hiệu ngay) và tạo QR ACTIVE mới trong cùng transaction; phiên
 *   đang chạy không bị ảnh hưởng (BR-S05). Audit QR_ROTATED.
 * - Thu hồi: QR ACTIVE -> REVOKED, bắt buộc lý do; bàn không còn QR nào dùng được cho tới khi sinh mã mới. Audit QR_REVOKED.
 * - Xuất PNG/PDF: dựng lại URL từ token dẫn xuất; ghi audit QR_EXPORTED (URL chứa token).
 */
@Service
class QrCodeService(
    private val qrCodeRepository: QrCodeRepository,
    private val tableRepository: DiningTableRepository,
    private val codec: QrTokenCodec,
    private val imageRenderer: QrImageRenderer,
    private val pdfRenderer: QrPdfRenderer,
    private val properties: QrProperties,
    private val auditService: AuditService,
    private val dbClock: DbClock,
    private val jdbc: JdbcTemplate,
) {
    /** QR ACTIVE của bàn kèm URL đầy đủ (client có thể tự render QR). */
    @Transactional(readOnly = true)
    fun active(tableId: Long): QrCodeResponse {
        loadTable(tableId)
        val qr = activeQr(tableId)
        return describe(qr)
    }

    /** Lịch sử QR của bàn (mới nhất trước); URL chỉ có ở QR ACTIVE. */
    @Transactional(readOnly = true)
    fun history(tableId: Long): List<QrCodeResponse> {
        loadTable(tableId)
        return qrCodeRepository.findAllByTableIdOrderByCreatedAtDescIdDesc(tableId)
            .map { if (it.status == QrCodeStatus.ACTIVE) describe(it) else QrCodeResponse.of(it) }
    }

    /** Sinh QR cho bàn chưa có QR ACTIVE (bàn mới, hoặc sau khi thu hồi). */
    @Transactional
    fun generate(actorUserId: Long, tableId: Long): QrCodeResponse {
        codec.requireConfigured()
        lockTable(tableId)
        if (qrCodeRepository.findFirstByTableIdAndStatus(tableId, QrCodeStatus.ACTIVE) != null) {
            throw BusinessException(ErrorCode.QR_ALREADY_ACTIVE)
        }
        val created = insertActive(tableId, actorUserId)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = TableAuditActions.QR_GENERATED,
                entityType = TableAuditEntityTypes.QR_CODE,
                entityId = created.id,
                newValue = mapOf("tableId" to tableId, "status" to QrCodeStatus.ACTIVE),
            ),
        )
        return describe(created)
    }

    /** FR-QR-02: xoay mã. Bàn phải đang có QR ACTIVE (nếu không: 409 QR_CODE_NOT_ACTIVE, dùng sinh mới). */
    @Transactional
    fun rotate(actorUserId: Long, tableId: Long, request: RotateQrRequest): QrCodeResponse {
        codec.requireConfigured()
        lockTable(tableId)
        val old = activeQr(tableId)
        val reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }
        deactivate(old, QrCodeStatus.ROTATED, actorUserId, reason)
        val created = insertActive(tableId, actorUserId)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = TableAuditActions.QR_ROTATED,
                entityType = TableAuditEntityTypes.QR_CODE,
                entityId = old.id,
                oldValue = mapOf("qrCodeId" to old.id, "status" to QrCodeStatus.ACTIVE),
                newValue = mapOf("qrCodeId" to old.id, "status" to QrCodeStatus.ROTATED, "replacedBy" to created.id, "tableId" to tableId),
                reason = reason,
            ),
        )
        return describe(created)
    }

    /** FR-QR-02: thu hồi mã (lý do bắt buộc). Không tạo mã mới. */
    @Transactional
    fun revoke(actorUserId: Long, tableId: Long, request: RevokeQrRequest): QrCodeResponse {
        lockTable(tableId)
        val qr = activeQr(tableId)
        val reason = requireNotNull(request.reason).trim()
        deactivate(qr, QrCodeStatus.REVOKED, actorUserId, reason)
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = TableAuditActions.QR_REVOKED,
                entityType = TableAuditEntityTypes.QR_CODE,
                entityId = qr.id,
                oldValue = mapOf("status" to QrCodeStatus.ACTIVE),
                newValue = mapOf("status" to QrCodeStatus.REVOKED, "tableId" to tableId),
                reason = reason,
            ),
        )
        return QrCodeResponse.of(qr)
    }

    /** Ảnh PNG của QR ACTIVE, cạnh [size] px trong [min, max] cấu hình (mặc định default-size). */
    @Transactional
    fun png(actorUserId: Long, tableId: Long, size: Int?): QrExport {
        codec.requireConfigured()
        val image = properties.image
        val px = size ?: image.defaultSize
        if (px !in image.minSize..image.maxSize) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                details = listOf(ErrorDetail("size", "Kích thước ảnh từ ${image.minSize} đến ${image.maxSize} px")),
            )
        }
        val table = loadTable(tableId)
        val qr = activeQr(tableId)
        val url = reproduceUrl(qr)
        val bytes = imageRenderer.renderPng(url, px, image.margin)
        recordExport(actorUserId, "PNG", listOf(tableId))
        return QrExport("qr-${table.code}.png", bytes)
    }

    /**
     * PDF in QR: một bàn mỗi trang. [tableIds] rỗng/null = mọi bàn đang dùng có QR ACTIVE (theo khu vực, thứ tự).
     * Bàn trong danh sách mà không có QR ACTIVE / không dựng lại được -> lỗi cho cả yêu cầu (không in thiếu âm thầm).
     */
    @Transactional
    fun pdf(actorUserId: Long, tableIds: List<Long>?): QrExport {
        codec.requireConfigured()
        val maxTables = properties.pdf.maxTables
        val requested = tableIds?.distinct().orEmpty()
        if (requested.size > maxTables) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                details = listOf(ErrorDetail("tableIds", "Tối đa $maxTables bàn mỗi lần in")),
            )
        }
        val tables: List<DiningTable> = if (requested.isEmpty()) {
            tableRepository.findAllByActiveTrueOrderByAreaAscDisplayOrderAscIdAsc()
        } else {
            val found = tableRepository.findAllById(requested).associateBy { requireNotNull(it.id) }
            requested.map { id -> found[id] ?: throw ResourceNotFoundException("bàn", id) }
        }
        val qrByTable = tables.mapNotNull { it.id }.takeIf { it.isNotEmpty() }
            ?.let { ids -> qrCodeRepository.findAllByTableIdInAndStatus(ids, QrCodeStatus.ACTIVE).associateBy { it.tableId } }
            ?: emptyMap()
        val printable = if (requested.isEmpty()) tables.filter { it.id in qrByTable } else tables
        if (printable.isEmpty()) throw BusinessException(ErrorCode.QR_CODE_NOT_ACTIVE, "Không có bàn nào có mã QR đang hoạt động để in")
        if (printable.size > maxTables) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                details = listOf(ErrorDetail("tableIds", "Có ${printable.size} bàn, tối đa $maxTables bàn mỗi lần in - hãy chọn bớt")),
            )
        }
        val pages = printable.map { table ->
            val qr = qrByTable[table.id] ?: throw BusinessException(
                ErrorCode.QR_CODE_NOT_ACTIVE, "Bàn ${table.code} chưa có mã QR đang hoạt động",
            )
            QrPrintPage(table.code, table.displayName, table.area, reproduceUrl(qr))
        }
        val bytes = pdfRenderer.render(pages)
        val ids = printable.map { requireNotNull(it.id) }
        recordExport(actorUserId, "PDF", ids)
        val fileName = if (printable.size == 1) "qr-${printable.first().code}.pdf" else "qr-ban-${printable.size}.pdf"
        return QrExport(fileName, bytes)
    }

    // ------------------------------------------------------------------------------------------ nội bộ

    private fun loadTable(tableId: Long): DiningTable =
        tableRepository.findById(tableId).orElseThrow { ResourceNotFoundException("bàn", tableId) }

    private fun lockTable(tableId: Long): DiningTable =
        tableRepository.findByIdForUpdate(tableId) ?: throw ResourceNotFoundException("bàn", tableId)

    private fun activeQr(tableId: Long): QrCode =
        qrCodeRepository.findFirstByTableIdAndStatus(tableId, QrCodeStatus.ACTIVE)
            ?: throw BusinessException(ErrorCode.QR_CODE_NOT_ACTIVE)

    /**
     * Tạo QR ACTIVE mới: lấy trước id từ sequence identity để dẫn xuất token rồi INSERT kèm token_hash
     * (cột token_hash NOT NULL, không sửa được sau khi tạo). Thay đổi JPA trước đó đã được flush (saveAndFlush) nên
     * partial unique index thấy QR cũ đã ROTATED.
     */
    private fun insertActive(tableId: Long, actorUserId: Long): QrCode {
        val id = requireNotNull(jdbc.queryForObject(NEXT_ID_SQL, Long::class.java))
        val token = codec.tokenFor(id)
        jdbc.update(
            "INSERT INTO qr_code (id, table_id, token_hash, status, created_by) VALUES (?, ?, ?, 'ACTIVE', ?)",
            id, tableId, codec.hashOf(token), actorUserId,
        )
        return qrCodeRepository.findById(id).orElseThrow { IllegalStateException("Không đọc lại được qr_code $id") }
    }

    private fun deactivate(qr: QrCode, status: QrCodeStatus, actorUserId: Long, reason: String?) {
        qr.status = status
        qr.deactivatedBy = actorUserId
        qr.deactivatedAt = dbClock.now()
        qr.deactivationReason = reason
        qrCodeRepository.saveAndFlush(qr)
    }

    private fun describe(qr: QrCode): QrCodeResponse {
        if (qr.status != QrCodeStatus.ACTIVE || !codec.configured) return QrCodeResponse.of(qr)
        val token = codec.reproduce(requireNotNull(qr.id), qr.tokenHash)
        return QrCodeResponse.of(qr, url = token?.let(codec::urlFor), reproducible = token != null)
    }

    private fun reproduceUrl(qr: QrCode): String {
        val token = codec.reproduce(requireNotNull(qr.id), qr.tokenHash)
            ?: throw BusinessException(ErrorCode.QR_TOKEN_NOT_REPRODUCIBLE)
        return codec.urlFor(token)
    }

    private fun recordExport(actorUserId: Long, format: String, tableIds: List<Long>) {
        auditService.record(
            AuditActor.User(actorUserId),
            AuditEvent(
                action = TableAuditActions.QR_EXPORTED,
                entityType = TableAuditEntityTypes.QR_CODE,
                entityId = null,
                newValue = mapOf("format" to format, "tableIds" to tableIds),
            ),
        )
    }

    companion object {
        /** Sequence của cột identity qr_code.id (GENERATED BY DEFAULT cho phép chèn id tường minh). */
        const val NEXT_ID_SQL = "SELECT nextval(pg_get_serial_sequence('qr_code', 'id'))"
    }
}
