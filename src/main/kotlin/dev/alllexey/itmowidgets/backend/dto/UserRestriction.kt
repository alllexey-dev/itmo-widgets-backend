package dev.alllexey.itmowidgets.backend.dto

import dev.alllexey.itmowidgets.backend.model.*
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
