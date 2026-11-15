package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkRevision
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationActor
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewRevisionStatus
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

/**
 * A teacher review in the moderation queue; [excerpt] is the first 160 characters of the reviewed revision's text and
 * [revisionStatus] its status. [teacherName] is the name of an active Reviews copy, otherwise the official name,
 * resolved after the transaction and never stored; null when unknown or not looked up yet.
 */
data class AdminReviewSummary(
    val id: UUID,
    val teacherIsu: Int,
    val subjectTitle: String?,
    val excerpt: String,
    val score: Int,
    val hidden: Boolean,
    val anonymous: Boolean,
    val revisionStatus: ReviewRevisionStatus,
    val teacherName: String?,
)

/** The latest resolving decision of a resolved case; [moderatorIsu] and [moderatorName] are null for a POLICY one. */
data class AdminCaseOutcome(
    val action: ModerationAction,
    val actor: ModerationActor,
    val decidedAt: Instant,
    val moderatorIsu: Int?,
    val moderatorName: String?,
    val note: String?,
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
    /** Null for an open or withdrawn case. */
    val outcome: AdminCaseOutcome?,
    /** The case whose HIDE_ALL_BY_USER decision withdrew this one; null for any other case. */
    val withdrawnByCaseId: UUID?,
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
    val revokedByName: String?,
    val active: Boolean,
    /** The case whose RESTRICT_USER decision created the restriction. */
    val caseId: UUID,
)
