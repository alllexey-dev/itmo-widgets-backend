package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ModerationCaseRepository : JpaRepository<ModerationCaseEntity, UUID> {
    /** Policy switches, threshold counts and case decisions share one transaction-scoped lock per target type. */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('moderation:' || :targetType, 0))", nativeQuery = true)
    fun lockTargetType(targetType: String): Int

    @Query("SELECT c FROM ModerationCaseEntity c WHERE c.targetType = :targetType AND c.targetId = :targetId AND c.status = 'OPEN'")
    fun findOpen(targetType: ModerationTargetType, targetId: UUID): ModerationCaseEntity?

    fun findAllByStatusOrderByOpenedAt(status: ModerationCaseStatus): List<ModerationCaseEntity>

    @Query(value = "SELECT id FROM moderation_cases WHERE id = :id FOR UPDATE", nativeQuery = true)
    fun lockById(id: UUID): UUID?
}
