package dev.alllexey.itmowidgets.backend.feature.moderation.web

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import java.time.Instant
import java.util.UUID

data class RestrictionRequest(val capability: RestrictionCapability, val days: Int? = null)

/**
 * [flowId] only with APPROVE of a FLOW link revision: one of the revision's flow options to show it to instead of the
 * submitted flow (`ModerationTarget.approvalFlow`).
 */
data class ModerationDecisionRequest(
    val action: ModerationAction,
    val note: String? = null,
    val restriction: RestrictionRequest? = null,
    val flowId: Long? = null,
)
