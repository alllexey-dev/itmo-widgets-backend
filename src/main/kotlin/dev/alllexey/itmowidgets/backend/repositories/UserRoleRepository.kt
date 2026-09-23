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
}
