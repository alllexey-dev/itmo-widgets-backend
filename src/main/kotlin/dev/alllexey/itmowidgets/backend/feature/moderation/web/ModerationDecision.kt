package dev.alllexey.itmowidgets.backend.feature.moderation.web

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationActor
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import java.time.Instant
import java.util.UUID

data class ModerationDecision(
    val id: UUID,
    val moderatorId: UUID?,
    val action: ModerationAction,
    val note: String?,
    val restriction: RestrictionRequest?,
    val createdAt: Instant,
    val actor: ModerationActor = ModerationActor.MODERATOR,
)

fun ModerationDecisionEntity.toDto() = ModerationDecision(id, moderator?.id, action, note,
    restrictionCapability?.let { RestrictionRequest(it, restrictionDays) }, createdAt, actor)
