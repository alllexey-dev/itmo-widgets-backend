package dev.alllexey.itmowidgets.backend.feature.moderation.persistence

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionRevertEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ModerationDecisionRevertRepository : JpaRepository<ModerationDecisionRevertEntity, UUID> {
    fun findAllByDecisionIdIn(decisionIds: Collection<UUID>): List<ModerationDecisionRevertEntity>
}
