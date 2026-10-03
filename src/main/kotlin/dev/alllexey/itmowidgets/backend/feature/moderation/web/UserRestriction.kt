package dev.alllexey.itmowidgets.backend.feature.moderation.web

import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.moderation.model.UserRestrictionEntity
import java.time.Instant
import java.util.UUID

data class UserRestriction(
    val id: UUID,
    val capability: RestrictionCapability,
    val reason: String,
    val startsAt: Instant,
    val expiresAt: Instant?,
)

fun UserRestrictionEntity.toDto() = UserRestriction(id, capability, reason, startsAt, expiresAt)
