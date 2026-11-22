package com.qrorder.table.repository

import com.qrorder.menu.repository.MenuSpecifications
import com.qrorder.table.domain.DiningTable
import com.qrorder.table.domain.QrCode
import com.qrorder.table.domain.QrCodeStatus
import com.qrorder.table.domain.SessionDevice
import com.qrorder.table.domain.SessionStatus
import com.qrorder.table.domain.TableSession
import com.qrorder.table.domain.TableStatus
import jakarta.persistence.LockModeType
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * BE-07: `qr_code.token_hash` là CHAR(64) (bpchar). Ép kiểu tham số về bpchar để PostgreSQL so sánh bằng toán tử của
 * bpchar và dùng index unique `uq_qr_code_token_hash` (tham số kiểu text sẽ khiến cột bị ép sang text -> Seq Scan).
 * Không lọc status trong câu truy vấn để chắc chắn dùng index unique; service kiểm tra status ACTIVE.
 * Dùng chung với test kiểm tra kế hoạch truy vấn (TableSessionJoinIT).
 */
const val QR_FIND_BY_TOKEN_HASH_SQL = "SELECT * FROM qr_code WHERE token_hash = CAST(:hash AS bpchar)"

interface DiningTableRepository : JpaRepository<DiningTable, Long>, JpaSpecificationExecutor<DiningTable> {
    /**
     * Khóa dòng bàn (SELECT ... FOR UPDATE). Mọi thao tác đổi phiên/trạng thái bàn khóa bàn TRƯỚC rồi mới khóa phiên
     * (join, mở phiên, force-close, Scheduler) để tuần tự hóa theo bàn và tránh deadlock (BR-S01).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DiningTable t where t.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): DiningTable?

    fun existsByCodeIgnoreCase(code: String): Boolean

    fun existsByCodeIgnoreCaseAndIdNot(code: String, id: Long): Boolean

    /** Sơ đồ bàn (FR-TBL-02): bàn đang dùng, theo khu vực rồi thứ tự hiển thị. */
    fun findAllByActiveTrueOrderByAreaAscDisplayOrderAscIdAsc(): List<DiningTable>
}

interface QrCodeRepository : JpaRepository<QrCode, Long> {
    @Query(value = QR_FIND_BY_TOKEN_HASH_SQL, nativeQuery = true)
    fun findByTokenHash(@Param("hash") hash: String): QrCode?

    fun findFirstByTableIdAndStatus(tableId: Long, status: QrCodeStatus): QrCode?

    fun findAllByTableIdOrderByCreatedAtDescIdDesc(tableId: Long): List<QrCode>

    fun findAllByTableIdInAndStatus(tableIds: Collection<Long>, status: QrCodeStatus): List<QrCode>
}

interface TableSessionRepository : JpaRepository<TableSession, Long>, JpaSpecificationExecutor<TableSession> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TableSession s where s.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): TableSession?

    /**
     * Bàn của phiên, đọc dạng giá trị vô hướng (không nạp entity vào persistence context) để khóa bàn trước rồi mới
     * khóa và nạp phiên bằng [findByIdForUpdate] (dữ liệu phiên luôn mới nhất sau khi có khóa).
     */
    @Query("select s.tableId from TableSession s where s.id = :id")
    fun findTableIdById(@Param("id") id: Long): Long?

    /** Phiên đang hoạt động (OPEN/BILLING) của bàn; tối đa một (ux_table_session_active_per_table). */
    fun findFirstByTableIdAndStatusIn(tableId: Long, statuses: Collection<SessionStatus>): TableSession?

    fun findAllByTableIdInAndStatusIn(tableIds: Collection<Long>, statuses: Collection<SessionStatus>): List<TableSession>

    /**
     * Ứng viên Scheduler (BR-S07): phiên OPEN idle quá [idleMinutes] phút theo đồng hồ DB. Dùng partial index
     * ix_table_session_idle_scan. Phiên BILLING không bao giờ tự hết hạn. Phiên chưa gắn needs_attention xếp trước để
     * các phiên đã gắn cờ (được quét lại để EXPIRED khi đơn đã xử lý xong) không chiếm hết lô.
     */
    @Query(
        value = """SELECT id FROM table_session
                    WHERE status = 'OPEN' AND last_activity_at < now() - (:idleMinutes * interval '1 minute')
                    ORDER BY needs_attention, last_activity_at, id
                    LIMIT :limit""",
        nativeQuery = true,
    )
    fun findIdleOpenSessionIds(@Param("idleMinutes") idleMinutes: Int, @Param("limit") limit: Int): List<Long>
}

interface SessionDeviceRepository : JpaRepository<SessionDevice, Long> {
    fun findBySessionIdAndDeviceId(sessionId: Long, deviceId: String): SessionDevice?

    fun countBySessionId(sessionId: Long): Long

    @Query("select d.sessionId as sessionId, count(d) as total from SessionDevice d where d.sessionId in :ids group by d.sessionId")
    fun countBySessionIds(@Param("ids") sessionIds: Collection<Long>): List<SessionDeviceCount>
}

interface SessionDeviceCount {
    val sessionId: Long
    val total: Long
}

/** Bộ lọc danh sách bàn và phiên (Criteria, tham số đã thoát ký tự LIKE). */
object TableSpecifications {
    private const val LIKE_ESCAPE = '\\'

    fun tables(keyword: String?, area: String?, status: TableStatus?, active: Boolean?): Specification<DiningTable> =
        Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            if (status != null) predicates += cb.equal(root.get<TableStatus>("status"), status)
            if (active != null) predicates += cb.equal(root.get<Boolean>("active"), active)
            area?.trim()?.takeIf { it.isNotEmpty() }?.let { predicates += cb.equal(cb.lower(root.get("area")), it.lowercase()) }
            MenuSpecifications.containsPattern(keyword)?.let { pattern ->
                predicates += cb.or(
                    cb.like(cb.lower(root.get("code")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("displayName")), pattern, LIKE_ESCAPE),
                )
            }
            cb.and(*predicates.toTypedArray())
        }

    fun sessions(statuses: Collection<SessionStatus>, tableId: Long?, needsAttention: Boolean?): Specification<TableSession> =
        Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            if (statuses.isNotEmpty()) predicates += root.get<SessionStatus>("status").`in`(statuses)
            if (tableId != null) predicates += cb.equal(root.get<Long>("tableId"), tableId)
            if (needsAttention != null) predicates += cb.equal(root.get<Boolean>("needsAttention"), needsAttention)
            cb.and(*predicates.toTypedArray())
        }
}
