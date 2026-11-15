package dev.alllexey.itmowidgets.backend.feature.moderation.persistence

import dev.alllexey.itmowidgets.backend.feature.admin.persistence.CaseOutcomeRow
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ModerationDecisionRepository : JpaRepository<ModerationDecisionEntity, UUID> {
    fun findAllByCaseIdOrderByCreatedAt(caseId: UUID): List<ModerationDecisionEntity>

    /**
     * The latest decision among [resolving] (action names, see [ModerationAction.resolvesCase]) of each of [caseIds]
     * that has one, with its moderator. Every condition a decision must meet to count is a predicate of the WHERE clause.
     */
    @Query(
        value = """
        SELECT DISTINCT ON (d.case_id) d.case_id AS caseId, d.action AS action, d.actor AS actor, d.created_at AS decidedAt,
               u.isu AS moderatorIsu, u.name AS moderatorName, d.note AS note
        FROM moderation_decisions d LEFT JOIN users u ON u.id = d.moderator_id
        WHERE d.case_id IN (:caseIds)
          AND d.action IN (:resolving)
        ORDER BY d.case_id, d.created_at DESC, d.id DESC
        """,
        nativeQuery = true,
    )
    fun findOutcomes(caseIds: Collection<UUID>, resolving: Collection<String>): List<CaseOutcomeRow>
}
