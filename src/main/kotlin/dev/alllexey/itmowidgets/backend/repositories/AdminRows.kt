package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.model.SportUpdateOutcome
import java.time.Instant
import java.util.UUID

// Constructor projections for admin lists: they never load User entities with their eager settings.

data class UserSummaryRow(val id: UUID, val isu: Int, val name: String?, val pictureUrl: String?, val createdAt: Instant)

data class UserGroupRow(val userId: UUID, val name: String, val course: Int, val facultyShortName: String)

data class AdminRestrictionRow(
    val id: UUID,
    val userId: UUID,
    val capability: RestrictionCapability,
    val reason: String,
    val startsAt: Instant,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
    val revokedByIsu: Int?,
    val caseId: UUID,
)

data class TargetCountRow(val targetId: UUID, val count: Long)

data class SportOutcomeRow(val outcome: SportUpdateOutcome, val count: Long, val totalDurationMillis: Long)

data class SportErrorRow(val category: SportUpdateErrorCategory, val count: Long)

/** A native aggregate row: a label (a day as `YYYY-MM-DD` or a status name) and its count. */
interface LabelCount {
    val label: String
    val total: Long
}
