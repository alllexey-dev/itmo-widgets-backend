package dev.alllexey.itmowidgets.backend.dto

import dev.alllexey.itmowidgets.backend.model.*
import java.time.Instant
import java.util.UUID

data class ModerationCase(
    val id: UUID,
    val targetType: ModerationTargetType,
    val status: ModerationCaseStatus,
    val reason: ModerationCaseReason,
    val openedAt: Instant,
    /** Deleted targets leave the case and append-only decisions available without retaining link content. */
    val target: ModerationCaseTarget?,
    val decisions: List<ModerationDecision>,
)
