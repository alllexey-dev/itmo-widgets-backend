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
    /** When the decision was reverted; a reverted decision stays in the list. */
    val revertedAt: Instant?,
    /** The end of the revert window, set only while the caller may still revert this decision. */
    val revertibleUntil: Instant?,
)

fun ModerationDecisionEntity.toDto(revertedAt: Instant?, revertibleUntil: Instant?) = ModerationDecision(
    id,
    moderator?.id,
    action,
    note,
    restrictionCapability?.let { RestrictionRequest(it, restrictionDays) },
    createdAt,
    actor,
    revertedAt,
    revertibleUntil,
)
