package com.qrorder.auth.repository

import com.qrorder.auth.domain.Permission
import com.qrorder.auth.domain.RefreshToken
import com.qrorder.auth.domain.User
import com.qrorder.auth.domain.UserPermission
import com.qrorder.auth.domain.UserRole
import com.qrorder.auth.domain.UserStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/** Ảnh chụp tối thiểu để filter JWT kiểm tra tài khoản còn hiệu lực ở mỗi request (BR-A04). */
interface UserAuthSnapshot {
    val id: Long
    val role: UserRole
    val status: UserStatus
}

interface UserRepository : JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    @Query("select u.id as id, u.role as role, u.status as status from User u where u.id = :id")
    fun findAuthSnapshot(@Param("id") id: Long): UserAuthSnapshot?

    @Query("select u from User u where lower(u.username) = lower(:username)")
    fun findByUsernameIgnoreCase(@Param("username") username: String): User?

    /** Đếm ADMIN hoạt động (BR-A09) - kiểm tra sớm; trigger `trg_users_keep_one_active_admin` là hàng rào cuối. */
    fun countByRoleAndStatus(role: UserRole, status: UserStatus): Long

    /**
     * Ghi last_login_at bằng now() của database, KHÔNG qua entity để không tăng version (đăng nhập
     * không được làm hỏng optimistic lock của Admin đang sửa user) và không đổi updated_at.
     * clearAutomatically: entity User đang nạp trở nên cũ, phải đọc lại nếu cần last_login_at mới.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE users SET last_login_at = now() WHERE id = :id", nativeQuery = true)
    fun touchLastLogin(@Param("id") id: Long): Int
}

interface UserPermissionRepository : JpaRepository<UserPermission, Long> {

    /** Permission còn hiệu lực (revoked_at IS NULL) - dùng index ux_user_permission_active. */
    fun existsByUserIdAndPermissionAndRevokedAtIsNull(userId: Long, permission: Permission): Boolean

    @Query("select p.permission from UserPermission p where p.userId = :userId and p.revokedAt is null")
    fun findActivePermissions(@Param("userId") userId: Long): List<Permission>

    fun findByUserIdAndPermissionAndRevokedAtIsNull(userId: Long, permission: Permission): UserPermission?

    /** Bản cấp còn hiệu lực của nhiều user một lần (danh sách user, tránh N+1). */
    fun findByUserIdInAndRevokedAtIsNull(userIds: Collection<Long>): List<UserPermission>

    /** Bản cấp còn hiệu lực của user. */
    fun findByUserIdAndRevokedAtIsNullOrderByPermissionAsc(userId: Long): List<UserPermission>

    /** Toàn bộ lịch sử cấp/thu hồi của user (mới nhất trước). */
    fun findByUserIdOrderByGrantedAtDescIdDesc(userId: Long): List<UserPermission>

    /**
     * Thu hồi bản cấp: revoked_at = now() của database (cùng nguồn giờ với granted_at, CHECK
     * revoked_at >= granted_at - BE-03). Điều kiện revoked_at IS NULL: 0 dòng = đã bị thu hồi trước đó.
     * clearAutomatically để lần đọc sau trong cùng transaction thấy dữ liệu mới.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE user_permission SET revoked_at = now(), revoked_by = :revokedBy, revoke_reason = :reason " +
            "WHERE id = :id AND revoked_at IS NULL",
        nativeQuery = true,
    )
    fun revoke(@Param("id") id: Long, @Param("revokedBy") revokedBy: Long, @Param("reason") reason: String): Int
}

/**
 * Các câu UPDATE của rotation viết bằng SQL gốc để bám đúng quy ước DATABASE_DESIGN.md §5.3 (11)
 * và dùng now() của database (cùng nguồn giờ với issued_at).
 */
interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {

    fun findByTokenHash(tokenHash: String): RefreshToken?

    /** Bước 1 rotation: chỉ thành công (1 dòng) nếu token chưa bị thu hồi. 0 dòng = đã bị dùng/thu hồi. */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "UPDATE refresh_token SET revoked_at = now(), revoke_reason = 'ROTATED' " +
            "WHERE id = :id AND revoked_at IS NULL",
        nativeQuery = true,
    )
    fun markRotated(@Param("id") id: Long): Int

    /** Bước 3 rotation: gắn token kế tiếp cho token vừa ROTATED (trigger chỉ cho phép đúng một lần). */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "UPDATE refresh_token SET replaced_by_id = :nextId WHERE id = :id",
        nativeQuery = true,
    )
    fun linkReplacement(@Param("id") id: Long, @Param("nextId") nextId: Long): Int

    /** Thu hồi mọi token còn hiệu lực của một family (REUSE_DETECTED, LOGOUT). */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "UPDATE refresh_token SET revoked_at = now(), revoke_reason = :reason " +
            "WHERE family_id = :familyId AND revoked_at IS NULL",
        nativeQuery = true,
    )
    fun revokeActiveInFamily(@Param("familyId") familyId: UUID, @Param("reason") reason: String): Int

    /** Thu hồi mọi token còn hiệu lực của user (PERMISSION_CHANGED, PASSWORD_CHANGED, ADMIN_REVOKED). */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "UPDATE refresh_token SET revoked_at = now(), revoke_reason = :reason " +
            "WHERE user_id = :userId AND revoked_at IS NULL",
        nativeQuery = true,
    )
    fun revokeAllActiveForUser(@Param("userId") userId: Long, @Param("reason") reason: String): Int

    @Query("select count(t) from RefreshToken t where t.familyId = :familyId and t.revokedAt is null")
    fun countActiveInFamily(@Param("familyId") familyId: UUID): Long
}
