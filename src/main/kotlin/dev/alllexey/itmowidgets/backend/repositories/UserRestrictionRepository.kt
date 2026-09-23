package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface UserRestrictionRepository : JpaRepository<UserRestrictionEntity, UUID> {
    @Query("SELECT r FROM UserRestrictionEntity r WHERE r.user.id = :userId AND r.startsAt <= :now AND r.revokedAt IS NULL AND (r.expiresAt IS NULL OR r.expiresAt > :now) ORDER BY r.startsAt DESC")
    fun findActive(userId: UUID, now: Instant): List<UserRestrictionEntity>

    fun findAllByUserIdOrderByStartsAtDesc(userId: UUID): List<UserRestrictionEntity>
}
