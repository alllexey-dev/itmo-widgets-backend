package dev.alllexey.itmowidgets.backend.feature.moderation.persistence

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionRevertEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * The one definition, in SQL, of a decision that stands: the decision `d` (a row of `moderation_decisions`) has no row
 * in `moderation_decision_reverts`. A reverted decision is no outcome of moderation, so the queue outcome and the
 * decision statistics read only decisions that stand.
 */
const val DECISION_NOT_REVERTED = "NOT EXISTS (SELECT 1 FROM moderation_decision_reverts dr WHERE dr.decision_id = d.id)"

interface ModerationDecisionRevertRepository : JpaRepository<ModerationDecisionRevertEntity, UUID> {
    fun findAllByDecisionIdIn(decisionIds: Collection<UUID>): List<ModerationDecisionRevertEntity>
}
