package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ModerationReportRepository : JpaRepository<ModerationReportEntity, UUID> {
    @Query("SELECT COUNT(DISTINCT r.reporter.id) FROM ModerationReportEntity r WHERE r.targetType = :targetType AND r.targetId = :targetId AND r.dismissedAt IS NULL")
    fun countActiveDistinctReporters(targetType: ModerationTargetType, targetId: UUID): Long

    @Query("SELECT r FROM ModerationReportEntity r WHERE r.targetType = :targetType AND r.targetId = :targetId AND r.dismissedAt IS NULL ORDER BY r.createdAt")
    fun findActive(targetType: ModerationTargetType, targetId: UUID): List<ModerationReportEntity>

    @Query("SELECT COUNT(r) > 0 FROM ModerationReportEntity r WHERE r.targetType = :targetType AND r.targetId = :targetId AND r.reporter.id = :reporterId")
    fun existsByTargetAndReporter(targetType: ModerationTargetType, targetId: UUID, reporterId: UUID): Boolean

    @Query("SELECT r.targetId FROM ModerationReportEntity r WHERE r.targetType = :targetType AND r.targetId IN :targetIds AND r.reporter.id = :reporterId")
    fun findReportedTargetIds(targetType: ModerationTargetType, targetIds: Collection<UUID>, reporterId: UUID): List<UUID>

    fun countByReporterIdAndCreatedAtAfter(reporterId: UUID, after: Instant): Long
    fun countByReporterIdAndDismissedAtIsNotNull(reporterId: UUID): Long

    @Modifying
    @Query("UPDATE ModerationReportEntity r SET r.dismissedAt = :now WHERE r.targetType = :targetType AND r.targetId = :targetId AND r.dismissedAt IS NULL")
    fun dismissAll(targetType: ModerationTargetType, targetId: UUID, now: Instant): Int

    @Modifying
    @Query("DELETE FROM ModerationReportEntity r WHERE r.targetType = :targetType AND r.targetId = :targetId")
    fun deleteAllByTarget(targetType: ModerationTargetType, targetId: UUID): Int

    @Query("""
        SELECT new dev.alllexey.itmowidgets.backend.repositories.TargetCountRow(r.targetId, COUNT(r))
        FROM ModerationReportEntity r
        WHERE r.targetType = :targetType AND r.targetId IN :targetIds AND r.dismissedAt IS NULL
        GROUP BY r.targetId
    """)
    fun countActiveByTargets(targetType: ModerationTargetType, targetIds: Collection<UUID>): List<TargetCountRow>
}
