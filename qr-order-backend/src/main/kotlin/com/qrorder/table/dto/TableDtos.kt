package com.qrorder.table.dto

import com.qrorder.common.domain.RequesterType
import com.qrorder.table.domain.DiningTable
import com.qrorder.table.domain.QrCode
import com.qrorder.table.domain.QrCodeStatus
import com.qrorder.table.domain.SessionAttentionReason
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.domain.TableSession
import com.qrorder.table.domain.TableStatus
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.OffsetDateTime

/*
 * Hợp đồng API Giai đoạn 3 - Bàn, QR, Phiên bàn (Admin Dashboard, Staff App, Customer Web).
 * Quy ước chung (như Giai đoạn 1-2):
 * - Mọi request sửa phải gửi kèm `version` đã đọc; lệch version -> 409 CONCURRENT_MODIFICATION (NFR-05).
 * - Tên trường theo schema: bàn ngừng dùng = `active` (cột is_active); trạng thái bàn 4 giá trị (DEC-04 B).
 * - Thời điểm ISO-8601; id là số.
 */

private const val CODE_PATTERN = "^[A-Za-z0-9_-]{1,20}$"
private const val CODE_MESSAGE = "Mã bàn 1-20 ký tự, chỉ gồm chữ không dấu, số, '-' hoặc '_'"
private const val DEVICE_PATTERN = "^[A-Za-z0-9_-]{8,64}$"
private const val PIN_PATTERN = "^[0-9]{4}$"
private const val REASON_MAX = 1000
private const val MAX_DISPLAY_ORDER = 1_000_000L

// =============================================================================================== Bàn (FR-TBL-01..03)

data class TableResponse(
    val id: Long,
    val code: String,
    val displayName: String,
    val area: String?,
    val capacity: Int,
    val status: TableStatus,
    val active: Boolean,
    val displayOrder: Int,
    val statusChangedAt: OffsetDateTime,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?,
    val version: Long,
) {
    companion object {
        fun of(t: DiningTable) = TableResponse(
            id = requireNotNull(t.id),
            code = t.code,
            displayName = t.displayName,
            area = t.area,
            capacity = t.capacity.toInt(),
            status = t.status,
            active = t.active,
            displayOrder = t.displayOrder,
            statusChangedAt = t.statusChangedAt,
            createdAt = t.createdAt,
            updatedAt = t.updatedAt,
            version = t.version,
        )
    }
}

data class CreateTableRequest(
    @field:NotBlank(message = "Mã bàn là bắt buộc")
    @field:Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE)
    val code: String?,
    @field:NotBlank(message = "Tên bàn là bắt buộc")
    @field:Size(max = 50, message = "Tên bàn tối đa 50 ký tự")
    val displayName: String?,
    @field:Size(max = 50, message = "Khu vực tối đa 50 ký tự")
    val area: String? = null,
    @field:NotNull(message = "Sức chứa là bắt buộc")
    @field:Min(value = 1, message = "Sức chứa từ 1 đến 100")
    @field:Max(value = 100, message = "Sức chứa từ 1 đến 100")
    val capacity: Int?,
    @field:Min(value = 0, message = "Thứ tự hiển thị phải >= 0")
    @field:Max(value = MAX_DISPLAY_ORDER, message = "Thứ tự hiển thị quá lớn")
    val displayOrder: Int? = null,
)

data class UpdateTableRequest(
    @field:NotBlank(message = "Mã bàn là bắt buộc")
    @field:Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE)
    val code: String?,
    @field:NotBlank(message = "Tên bàn là bắt buộc")
    @field:Size(max = 50, message = "Tên bàn tối đa 50 ký tự")
    val displayName: String?,
    @field:Size(max = 50, message = "Khu vực tối đa 50 ký tự")
    val area: String? = null,
    @field:NotNull(message = "Sức chứa là bắt buộc")
    @field:Min(value = 1, message = "Sức chứa từ 1 đến 100")
    @field:Max(value = 100, message = "Sức chứa từ 1 đến 100")
    val capacity: Int?,
    @field:NotNull(message = "Thứ tự hiển thị là bắt buộc")
    @field:Min(value = 0, message = "Thứ tự hiển thị phải >= 0")
    @field:Max(value = MAX_DISPLAY_ORDER, message = "Thứ tự hiển thị quá lớn")
    val displayOrder: Int?,
    @field:NotNull(message = "version là bắt buộc")
    val version: Long?,
)

/** Ngừng sử dụng / dùng lại bàn (BR-S08, BR-A03: không xóa cứng). */
data class ChangeTableActiveRequest(
    @field:NotNull(message = "active là bắt buộc")
    val active: Boolean?,
    @field:NotNull(message = "version là bắt buộc")
    val version: Long?,
)

/** FR-TBL-03: xác nhận dọn xong (CLEANING -> AVAILABLE). Các trạng thái khác do phiên quyết định (§9.7). */
data class ChangeTableStatusRequest(
    @field:NotNull(message = "status là bắt buộc")
    val status: TableStatus?,
    @field:NotNull(message = "version là bắt buộc")
    val version: Long?,
)

/** Tóm tắt phiên đang hoạt động trên sơ đồ bàn. */
data class ActiveSessionSummary(
    val id: Long,
    val status: SessionStatus,
    val openedAt: OffsetDateTime?,
    val lastActivityAt: OffsetDateTime,
    val needsAttention: Boolean,
    val deviceCount: Long,
    val version: Long,
)

/** FR-TBL-02: một ô trên sơ đồ bàn (Staff App, Admin Dashboard). */
data class FloorPlanTableResponse(
    val table: TableResponse,
    val hasActiveQr: Boolean,
    val activeSession: ActiveSessionSummary?,
)

// =============================================================================================== QR (FR-QR-01, FR-QR-02)

/**
 * Thông tin QR. [url] (URL đầy đủ trong QR, client có thể tự render) chỉ có với QR ACTIVE khi đã cấu hình QR và dựng
 * lại được token; [reproducible] = false nghĩa là cần xoay mã mới để in (QR seed dev hoặc đã đổi QR_TOKEN_SECRET).
 */
data class QrCodeResponse(
    val id: Long,
    val tableId: Long,
    val status: QrCodeStatus,
    val createdAt: OffsetDateTime?,
    val createdBy: Long?,
    val deactivatedAt: OffsetDateTime?,
    val deactivatedBy: Long?,
    val deactivationReason: String?,
    val url: String?,
    val reproducible: Boolean?,
) {
    companion object {
        fun of(q: QrCode, url: String? = null, reproducible: Boolean? = null) = QrCodeResponse(
            id = requireNotNull(q.id),
            tableId = q.tableId,
            status = q.status,
            createdAt = q.createdAt,
            createdBy = q.createdBy,
            deactivatedAt = q.deactivatedAt,
            deactivatedBy = q.deactivatedBy,
            deactivationReason = q.deactivationReason,
            url = url,
            reproducible = reproducible,
        )
    }
}

data class RotateQrRequest(
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 1000 ký tự")
    val reason: String? = null,
)

data class RevokeQrRequest(
    @field:NotBlank(message = "Lý do thu hồi là bắt buộc")
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 1000 ký tự")
    val reason: String?,
)

// =============================================================================================== Phiên bàn (FR-SES-01..06)

/** POST /api/v1/public/sessions/join (Customer Web sau khi quét QR). */
data class JoinSessionRequest(
    @field:NotBlank(message = "qrToken là bắt buộc")
    @field:Pattern(regexp = "^[A-Za-z0-9_-]{8,128}$", message = "qrToken không hợp lệ")
    val qrToken: String?,
    @field:NotBlank(message = "deviceId là bắt buộc")
    @field:Pattern(regexp = DEVICE_PATTERN, message = "deviceId 8-64 ký tự [A-Za-z0-9_-] (khuyến nghị UUID)")
    val deviceId: String?,
    /** PIN 4 số (DEC-07 A), tùy chọn. Nếu gửi thì phải đúng PIN của phiên đang hoạt động. */
    @field:Pattern(regexp = PIN_PATTERN, message = "PIN gồm 4 chữ số")
    val pin: String? = null,
)

data class VerifyPinRequest(
    @field:NotBlank(message = "PIN là bắt buộc")
    @field:Pattern(regexp = PIN_PATTERN, message = "PIN gồm 4 chữ số")
    val pin: String?,
)

data class GuestTableInfo(
    val id: Long,
    val code: String,
    val displayName: String,
    val area: String?,
)

/**
 * Phiên nhìn từ phía khách. Không chứa PIN, thông tin nhân viên hay dữ liệu nội bộ.
 * [canOrder] = phiên OPEN (BILLING chỉ xem đơn, BR-S03). [pinRequired] && ![pinVerified]: client yêu cầu nhập PIN
 * trước khi gửi order (DEC-07 A, kiểm tra ở Giai đoạn 4).
 */
data class GuestSessionResponse(
    val sessionId: Long,
    val status: SessionStatus,
    val canOrder: Boolean,
    val openedAt: OffsetDateTime?,
    val table: GuestTableInfo,
    val deviceId: String,
    val pinRequired: Boolean,
    val pinVerified: Boolean,
)

/** Kết quả join / gia hạn Guest JWT (FR-AUTH-04). Client gửi `Authorization: Bearer <accessToken>`. */
data class GuestTokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: Instant,
    /** true nếu lần join này tạo phiên mới (bàn đang trống). */
    val newSession: Boolean,
    val session: GuestSessionResponse,
)

/** Chi tiết phiên cho nhân viên (gồm PIN để đọc cho khách - DEC-07 A). */
data class StaffSessionResponse(
    val id: Long,
    val tableId: Long,
    val tableCode: String?,
    val tableName: String?,
    val area: String?,
    val status: SessionStatus,
    val openedByType: RequesterType,
    val openedByUserId: Long?,
    val openedAt: OffsetDateTime?,
    val lastActivityAt: OffsetDateTime,
    val verifiedAt: OffsetDateTime?,
    val tablePin: String?,
    val needsAttention: Boolean,
    val attentionReason: SessionAttentionReason?,
    val attentionSetAt: OffsetDateTime?,
    val billingStartedAt: OffsetDateTime?,
    val closedAt: OffsetDateTime?,
    val closedBy: Long?,
    val closeReason: String?,
    val deviceCount: Long,
    val version: Long,
) {
    companion object {
        fun of(s: TableSession, table: DiningTable?, deviceCount: Long) = StaffSessionResponse(
            id = requireNotNull(s.id),
            tableId = s.tableId,
            tableCode = table?.code,
            tableName = table?.displayName,
            area = table?.area,
            status = s.status,
            openedByType = s.openedByType,
            openedByUserId = s.openedByUserId,
            openedAt = s.openedAt,
            lastActivityAt = s.lastActivityAt,
            verifiedAt = s.verifiedAt,
            tablePin = s.tablePin,
            needsAttention = s.needsAttention,
            attentionReason = s.attentionReason,
            attentionSetAt = s.attentionSetAt,
            billingStartedAt = s.billingStartedAt,
            closedAt = s.closedAt,
            closedBy = s.closedBy,
            closeReason = s.closeReason,
            deviceCount = deviceCount,
            version = s.version,
        )
    }
}

/** Force-close (BR-S06, FR-SES-03): lý do bắt buộc; [version] tùy chọn để chống thao tác trên dữ liệu cũ. */
data class ForceCloseSessionRequest(
    @field:NotBlank(message = "Lý do force-close là bắt buộc")
    @field:Size(max = REASON_MAX, message = "Lý do tối đa 1000 ký tự")
    val reason: String?,
    val version: Long? = null,
)
