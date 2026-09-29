package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.ModeratedTeacherReview
import dev.alllexey.itmowidgets.backend.dto.OwnTeacherReview
import dev.alllexey.itmowidgets.backend.dto.TeacherReview
import dev.alllexey.itmowidgets.backend.dto.TeacherReviewKind
import dev.alllexey.itmowidgets.backend.dto.TeacherReviewRevision
import dev.alllexey.itmowidgets.backend.dto.TeacherReviewStatus
import dev.alllexey.itmowidgets.backend.dto.UserData
import dev.alllexey.itmowidgets.backend.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.model.ReviewRevisionStatus
import dev.alllexey.itmowidgets.backend.model.ReviewVerification
import dev.alllexey.itmowidgets.backend.model.TeacherReviewEntity
import dev.alllexey.itmowidgets.backend.model.TeacherReviewRevisionEntity
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.ExternalTeacherReviewVoteRepository
import dev.alllexey.itmowidgets.backend.repositories.ModerationReportRepository
import dev.alllexey.itmowidgets.backend.repositories.TeacherReviewRevisionRepository
import dev.alllexey.itmowidgets.backend.repositories.TeacherReviewVoteRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Viewer-scoped DTOs of teacher reviews. Callers hold the transaction. */
@Service
class TeacherReviewViews(
    private val revisions: TeacherReviewRevisionRepository,
    private val votes: TeacherReviewVoteRepository,
    private val externalVotes: ExternalTeacherReviewVoteRepository,
    private val reports: ModerationReportRepository,
    private val privacy: UserPrivacyService,
    private val clock: Clock,
) {
    /** The approved revision others see: none for a hidden review or one never approved. */
    fun shown(review: TeacherReviewEntity): TeacherReviewRevisionEntity? =
        if (review.hiddenAt != null) null else revisions.findLatestApproved(review.id)

    /** Published content as [viewer] sees it; the name of the author only on reviews written under it. */
    fun published(viewer: User, reviews: List<TeacherReviewEntity>): List<TeacherReview> {
        val open = reviews.filter { it.hiddenAt == null }
        if (open.isEmpty()) return emptyList()
        val ids = open.map { it.id }
        val approved = revisions.findLatestApprovedIn(ids).associateBy { it.review.id }
        if (approved.isEmpty()) return emptyList()
        val myVotes = votes.findByUserAndReviews(viewer.id, ids).associate { it.id.reviewId to it.value.toInt() }
        val reported = reports.findReportedTargetIds(TYPE, approved.values.map { it.id }, viewer.id).toSet()
        val authors = HashMap<UUID, UserData>()
        return open.mapNotNull { review ->
            val shown = approved[review.id] ?: return@mapNotNull null
            TeacherReview(
                id = review.id,
                kind = TeacherReviewKind.COMMUNITY,
                subjectTitle = shown.subjectTitle,
                writtenOn = date(shown.submittedAt),
                writtenBeforeYear = null,
                text = shown.text,
                score = review.score,
                myVote = myVotes[review.id] ?: 0,
                verified = review.verification == ReviewVerification.VERIFIED,
                reportedByMe = shown.id in reported,
                author = if (review.anonymous) null else authors.getOrPut(review.author.id) { privacy.userDataFor(viewer, review.author) },
                sourceTitle = null,
                sourceLink = null,
            )
        }
    }

    /** Active Reviews copies: anonymous, never verified and never reportable. */
    fun external(viewer: User, rows: List<ExternalTeacherReviewEntity>): List<TeacherReview> {
        if (rows.isEmpty()) return emptyList()
        val myVotes = externalVotes.findByUserAndReviews(viewer.id, rows.map { it.id }).associate { it.id.reviewId to it.value.toInt() }
        return rows.map { row ->
            TeacherReview(
                id = row.id,
                kind = TeacherReviewKind.REVIEWS,
                subjectTitle = row.subjectTitle,
                writtenOn = row.writtenOn,
                writtenBeforeYear = row.writtenBeforeYear,
                text = row.text,
                score = row.score,
                myVote = myVotes[row.id] ?: 0,
                verified = false,
                reportedByMe = false,
                author = null,
                sourceTitle = row.sourceTitle,
                sourceLink = row.sourceLink,
            )
        }
    }

    /** The author's own review with its current content and review state. */
    fun own(review: TeacherReviewEntity): OwnTeacherReview {
        val latest = revisions.findLatest(review.id)
        val status = ownerStatus(review, latest)
        return OwnTeacherReview(
            id = review.id,
            subjectTitle = review.subjectTitle,
            text = review.text,
            anonymous = review.anonymous,
            status = status,
            reviewNote = latest?.note?.takeIf { status == TeacherReviewStatus.REJECTED },
            score = review.score,
            verified = review.verification == ReviewVerification.VERIFIED,
            writtenOn = date(latest?.submittedAt ?: review.updatedAt),
        )
    }

    /** The review next to a moderated revision; [ModeratedTeacherReview.teacherName] is resolved by the admin layer. */
    fun moderated(review: TeacherReviewEntity): ModeratedTeacherReview {
        val latest = revisions.findLatest(review.id)
        val status = ownerStatus(review, latest)
        return ModeratedTeacherReview(
            id = review.id,
            teacherIsu = review.teacherIsu,
            teacherName = null,
            anonymous = review.anonymous,
            status = status,
            reviewNote = latest?.note?.takeIf { status == TeacherReviewStatus.REJECTED },
            shown = revisions.findLatestApproved(review.id)?.let(::revision),
            score = review.score,
            hidden = review.hiddenAt != null,
            verification = review.verification,
            verifiedFlowId = review.verifiedFlowId,
        )
    }

    fun author(viewer: User, author: User): UserData = privacy.userDataFor(viewer, author)

    fun revision(entity: TeacherReviewRevisionEntity) = TeacherReviewRevision(entity.id, entity.review.id, entity.number,
        entity.subjectTitle, entity.text, entity.status, entity.submittedAt, entity.decidedAt, entity.note)

    /** A pending revision is always the newest one: a new revision withdraws the previous pending one. */
    fun ownerStatus(review: TeacherReviewEntity, latest: TeacherReviewRevisionEntity?): TeacherReviewStatus = when {
        review.hiddenAt != null -> TeacherReviewStatus.HIDDEN
        latest?.status == ReviewRevisionStatus.PENDING -> TeacherReviewStatus.PENDING
        latest?.status == ReviewRevisionStatus.REJECTED -> TeacherReviewStatus.REJECTED
        else -> TeacherReviewStatus.PUBLISHED
    }

    private fun date(instant: Instant): LocalDate = LocalDate.ofInstant(instant, clock.zone)

    private companion object {
        val TYPE = ModerationTargetType.TEACHER_REVIEW
    }
}
