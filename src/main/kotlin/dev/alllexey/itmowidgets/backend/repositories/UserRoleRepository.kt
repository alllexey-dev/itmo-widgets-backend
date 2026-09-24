package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface UserRoleRepository : JpaRepository<UserRoleEntity, UserRoleId> {
    @Query("SELECT COUNT(r) > 0 FROM UserRoleEntity r WHERE r.id.userId = :userId AND r.id.role = :role")
    fun existsByUserIdAndRole(userId: UUID, role: UserRole): Boolean

    @Query("SELECT r.id.role FROM UserRoleEntity r WHERE r.id.userId = :userId")
    fun findRolesOf(userId: UUID): List<UserRole>

    @Query("SELECT r.id FROM UserRoleEntity r WHERE r.id.userId IN :userIds")
    fun findRoleIdsOf(userIds: Collection<UUID>): List<UserRoleId>

    /** Returns 1 when the role was granted now, 0 when the user already had it. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        nativeQuery = true,
        value = "INSERT INTO user_roles (user_id, role, granted_at) VALUES (:userId, :role, :now) ON CONFLICT (user_id, role) DO NOTHING",
    )
    fun grant(userId: UUID, role: String, now: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM UserRoleEntity r WHERE r.id.userId = :userId AND r.id.role = :role")
    fun revoke(userId: UUID, role: UserRole): Int
}
