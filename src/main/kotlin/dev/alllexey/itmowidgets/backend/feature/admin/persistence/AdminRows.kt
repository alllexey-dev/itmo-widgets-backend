package dev.alllexey.itmowidgets.backend.feature.admin.persistence

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationActor
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateOutcome
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
    val revokedByName: String?,
    val caseId: UUID,
)

data class TargetCountRow(val targetId: UUID, val count: Long)

data class UserCountRow(val userId: UUID, val count: Long)

/** A native row of the latest resolving decision of a case, with its moderator's ISU and name. */
interface CaseOutcomeRow {
    val caseId: UUID
    val action: ModerationAction
    val actor: ModerationActor
    val decidedAt: Instant
    val moderatorIsu: Int?
    val moderatorName: String?
    val note: String?
}

data class SportOutcomeRow(val outcome: SportUpdateOutcome, val count: Long, val totalDurationMillis: Long)

data class SportErrorRow(val category: SportUpdateErrorCategory, val count: Long)

/** A native aggregate row: a label (a day as `YYYY-MM-DD` or a status name) and its count. */
interface LabelCount {
    val label: String
    val total: Long
}
