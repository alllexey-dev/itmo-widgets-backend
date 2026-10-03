package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkRevision
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import java.time.Instant
import java.util.UUID

/** Link state next to a reviewed revision; the content itself is in the revision. */
data class AdminLinkSummary(
    val id: UUID,
    val subjectId: Long,
    val subjectName: String,
    val periodKey: String,
    val score: Int,
    val hidden: Boolean,
)

/** A teacher review in the moderation queue; [excerpt] is the first 160 characters of the revision text. */
data class AdminReviewSummary(
    val id: UUID,
    val teacherIsu: Int,
    val subjectTitle: String?,
    val excerpt: String,
    val score: Int,
    val hidden: Boolean,
    val anonymous: Boolean,
)

/**
 * A moderation queue row. A subject link row has [revision] and [link], a teacher review row has [review];
 * the target fields and the author are null when the target was deleted.
 */
data class AdminCaseItem(
    val id: UUID,
    val targetType: ModerationTargetType,
    val status: ModerationCaseStatus,
    val reason: ModerationCaseReason,
    val openedAt: Instant,
    val resolvedAt: Instant?,
    val revision: SubjectLinkRevision?,
    val link: AdminLinkSummary?,
    val review: AdminReviewSummary?,
    val author: AdminUserSummary?,
    /** Active (not dismissed) reports on the target. */
    val reportCount: Long,
)

data class AdminRestriction(
    val id: UUID,
    val user: AdminUserSummary,
    val capability: RestrictionCapability,
    val reason: String,
    val startsAt: Instant,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
    val revokedByIsu: Int?,
    val active: Boolean,
    /** The case whose RESTRICT_USER decision created the restriction. */
    val caseId: UUID,
)
