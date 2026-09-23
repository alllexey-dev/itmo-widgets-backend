package dev.alllexey.itmowidgets.backend.dto

import dev.alllexey.itmowidgets.backend.model.*
import java.time.Instant
import java.util.UUID

data class RestrictionRequest(val capability: RestrictionCapability, val days: Int? = null)
data class ModerationDecisionRequest(
    val action: ModerationAction,
    val note: String? = null,
    val restriction: RestrictionRequest? = null,
)
