package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReviewSummary
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationReportRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.service.CaseTargetSummary
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationReportService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationSettingsService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationTarget
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCaseTarget
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationReportRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.SubmitterHistory
import dev.alllexey.itmowidgets.backend.feature.moderation.web.TeacherReviewTarget
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewVoteEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewVoteId
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewRevisionStatus
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewVerification
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewFlowEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewFlowId
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewRevisionEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewVoteEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewVoteId
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalTeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalTeacherReviewVoteRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.IsuPotokTeacherRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewFlowRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRevisionRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewVoteRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.web.SaveTeacherReviewRequest
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewsResponse
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummaryLevel
import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.LessonRepository
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Teacher reviews: the author's own reviews with premoderated revisions, and the Reviews copies.
 * The review row holds the author's current content; every content change becomes an immutable revision
 * that always waits for a moderator, and other viewers see the latest approved revision. Case targets are
 * revisions. Anonymity lives on the row and applies at once without a revision.
 */
@Service
class TeacherReviewService(
    private val reviews: TeacherReviewRepository,
    private val revisions: TeacherReviewRevisionRepository,
    private val votes: TeacherReviewVoteRepository,
    private val flows: TeacherReviewFlowRepository,
    private val copies: ExternalTeacherReviewRepository,
    private val copyVotes: ExternalTeacherReviewVoteRepository,
    private val users: UserRepository,
    private val lessons: LessonRepository,
    private val potokTeachers: IsuPotokTeacherRepository,
    private val views: TeacherReviewViews,
    private val summaries: TeacherSummaryViews,
    private val restrictions: RestrictionService,
    private val settings: ModerationSettingsService,
    private val moderation: ModerationService,
    private val reports: ModerationReportService,
    private val reportRows: ModerationReportRepository,
    private val kick: ObjectProvider<ReviewVerificationKick>,
    private val clock: Clock,
) : ModerationTarget {

    @Transactional(readOnly = true)
    fun reviews(viewerId: UUID, isu: Int): TeacherReviewsResponse {
        if (isu <= 0) throw InvalidRequestDataException("ISU must be positive")
        val viewer = user(viewerId)
        val own = reviews.findByAuthorIdAndTeacherIsu(viewerId, isu)
        val others = reviews.findAllByTeacherIsuAndHiddenAtIsNull(isu).filter { it.author.id != viewerId }
        val active = copies.findAllByProviderAndTeacherIsuAndRemovedAtIsNull(ReviewsSyncStore.PROVIDER, isu)
        val listed = (views.published(viewer, others) + views.external(viewer, active)).sortedWith(ReviewOrder.RANKED)
        val restricted = restrictions.activeFor(viewerId).map { it.capability }.toSet()
        fun allowed(capability: RestrictionCapability) = capability !in restricted && RestrictionCapability.ALL !in restricted
        val self = viewer.isu == isu
        return TeacherReviewsResponse(
            teacherIsu = isu,
            providerUrl = ReviewTeachers.siteUrl(isu),
            reviews = listed,
            mine = own?.let(views::own),
            canWrite = !self && allowed(RestrictionCapability.WRITE_REVIEWS),
            canVote = !self && allowed(RestrictionCapability.VOTE),
            canReport = allowed(RestrictionCapability.REPORT),
            knownTeacher = lessons.existsTeacher(isu.toLong()) || potokTeachers.existsByIdTeacherIsu(isu) ||
                active.isNotEmpty() || reviews.existsPublishedForTeacher(isu),
            summary = summaries.shown(isu),
        )
    }

    /** Summary levels of up to 50 teachers for the dots next to their names; repeats are ignored. */
    @Transactional(readOnly = true)
    fun summaryLevels(isus: List<Int>): List<TeacherSummaryLevel> {
        val distinct = isus.distinct()
        if (distinct.isEmpty() || distinct.size > MAX_LEVEL_TEACHERS ||
            distinct.any { it !in ReviewTeachers.ISU_MIN..ReviewTeachers.ISU_MAX }
        ) {
            throw InvalidRequestDataException("Invalid teacher ISU list")
        }
        return summaries.levels(distinct)
    }

    /** Creates or edits the caller's review of the teacher; a content change becomes a pending revision. */
    @Transactional
    fun save(viewerId: UUID, isu: Int, request: SaveTeacherReviewRequest): TeacherReviewsResponse {
        requireTeacher(isu)
        val content = request.content()
        val viewer = user(viewerId)
        if (viewer.isu == isu) throw InvalidRequestDataException("Own reviews are not allowed")
        moderation.lock(TYPE)
        users.lockById(viewerId) ?: throw NotFoundException("User not found")
        reviews.lockByAuthorAndTeacher(viewerId, isu)
        val existing = reviews.findByAuthorIdAndTeacherIsu(viewerId, isu)
        val changed = existing == null || existing.subjectTitle != content.subjectTitle || existing.text != content.text
        val now = clock.instant()
        if (changed) {
            restrictions.require(viewerId, RestrictionCapability.WRITE_REVIEWS)
            if (revisions.countByAuthorSince(viewerId, now.minusSeconds(DAY_SECONDS)) >= settings.policy(TYPE).dailySubmissionLimit) {
                throw BusinessRuleException("Daily submission limit reached")
            }
        }
        val review = reviews.saveAndFlush(
            (
                existing ?: TeacherReviewEntity(
                    author = viewer, teacherIsu = isu,
                    subjectTitle = content.subjectTitle, text = content.text, anonymous = request.anonymous,
                    verification = ReviewVerification.PENDING, verificationDueAt = now, createdAt = now, updatedAt = now,
                )
                ).also {
                it.anonymous = request.anonymous
                if (it.verification == ReviewVerification.UNVERIFIED) {
                    it.verification = ReviewVerification.PENDING
                    it.verificationDueAt = now
                    it.verificationAttempts = 0
                }
                if (changed) {
                    it.subjectTitle = content.subjectTitle
                    it.text = content.text
                    it.updatedAt = now
                }
            },
        )
        flows.deleteAllByIdReviewId(review.id)
        flows.saveAll(content.flowIds.map { TeacherReviewFlowEntity(TeacherReviewFlowId(review.id, it)) })
        if (changed) submit(review, now)
        kickAfterCommit()
        return reviews(viewerId, isu)
    }

    /** Deletes the caller's review: open cases are withdrawn, reports and revisions removed; votes and flows cascade. */
    @Transactional
    fun delete(viewerId: UUID, isu: Int): TeacherReviewsResponse {
        if (isu <= 0) throw InvalidRequestDataException("ISU must be positive")
        moderation.lock(TYPE)
        reviews.lockByAuthorAndTeacher(viewerId, isu) ?: return reviews(viewerId, isu)
        val review = reviews.findByAuthorIdAndTeacherIsu(viewerId, isu) ?: return reviews(viewerId, isu)
        val history = revisions.findAllByReview(review.id)
        for (revision in history) {
            moderation.withdraw(TYPE, revision.id)
            reports.deleteAllFor(TYPE, revision.id)
        }
        revisions.deleteAll(history)
        reviews.delete(review)
        reviews.flush()
        return reviews(viewerId, isu)
    }

    /**
     * -1 or 1 replaces the caller's vote, 0 removes it, on another author's published review or an active
     * Reviews copy. A low score opens a VOTES case on the shown revision of an own review only.
     */
    @Transactional
    fun vote(viewerId: UUID, id: UUID, value: Int): TeacherReviewsResponse {
        if (value !in -1..1) throw InvalidRequestDataException("Vote must be -1, 0 or 1")
        restrictions.require(viewerId, RestrictionCapability.VOTE)
        moderation.lock(TYPE)
        val viewer = user(viewerId)
        if (reviews.lockById(id) != null) {
            val review = reviews.findById(id).orElseThrow { NotFoundException("Review not found") }
            val shown = othersReview(viewer, review)
            val key = TeacherReviewVoteId(id, viewerId)
            val existing = votes.findById(key).orElse(null)
            when {
                value == 0 -> existing?.let(votes::delete)
                existing != null -> existing.value = value.toShort()
                else -> votes.save(TeacherReviewVoteEntity(key, value.toShort(), clock.instant()))
            }
            votes.flush()
            review.score = votes.sumValues(id)
            reviews.save(review)
            if (review.score <= settings.policy(TYPE).voteThreshold) moderation.openCase(TYPE, shown.id, ModerationCaseReason.VOTES)
            return reviews(viewerId, review.teacherIsu)
        }
        val copy = activeCopy(id) ?: throw NotFoundException("Review not found")
        if (viewer.isu == copy.teacherIsu) throw BusinessRuleException("Not allowed on reviews about yourself")
        val key = ExternalTeacherReviewVoteId(id, viewerId)
        val existing = copyVotes.findById(key).orElse(null)
        when {
            value == 0 -> existing?.let(copyVotes::delete)
            existing != null -> existing.value = value.toShort()
            else -> copyVotes.save(ExternalTeacherReviewVoteEntity(key, value.toShort(), clock.instant()))
        }
        copyVotes.flush()
        copy.score = copyVotes.sumValues(id)
        copies.save(copy)
        return reviews(viewerId, copy.teacherIsu)
    }

    /** Reports the revision the caller currently sees; Reviews copies cannot be reported. */
    @Transactional
    fun report(viewerId: UUID, id: UUID, request: ModerationReportRequest): TeacherReviewsResponse {
        val review = reviews.findById(id).orElse(null)
        if (review == null) {
            if (activeCopy(id) != null) throw BusinessRuleException("Copied reviews cannot be reported")
            throw NotFoundException("Review not found")
        }
        if (review.author.id == viewerId) throw BusinessRuleException("Not allowed on own reviews")
        val shown = views.shown(review) ?: throw NotFoundException("Review not found")
        reports.report(viewerId, TYPE, shown.id, request)
        return reviews(viewerId, review.teacherIsu)
    }

    override fun targetType() = TYPE

    override fun ownerId(targetId: UUID): UUID = revision(targetId).review.author.id

    override fun isReportable(targetId: UUID, reporterId: UUID): Boolean {
        val revision = revisions.findById(targetId).orElse(null) ?: return false
        return revision.review.author.id != reporterId && views.shown(revision.review)?.id == revision.id
    }

    /** Teacher reviews are always premoderated. */
    override fun canAutoApprove(targetId: UUID): Boolean = false

    /**
     * APPROVE publishes a pending revision (an approved one stays as is). REJECT declines a pending
     * revision or withdraws an approved one, so others fall back to the previous approved content.
     */
    override fun apply(action: ModerationAction, targetId: UUID, decision: ModerationDecisionEntity) {
        val revision = revision(targetId)
        val review = revision.review
        when (action) {
            ModerationAction.APPROVE -> when (revision.status) {
                ReviewRevisionStatus.PENDING -> decide(revision, ReviewRevisionStatus.APPROVED, decision)
                ReviewRevisionStatus.APPROVED -> Unit
                else -> throw BusinessRuleException("Only a pending revision can be approved")
            }

            ModerationAction.REJECT -> when (revision.status) {
                ReviewRevisionStatus.PENDING, ReviewRevisionStatus.APPROVED -> decide(revision, ReviewRevisionStatus.REJECTED, decision)
                else -> throw BusinessRuleException("Only a pending or approved revision can be rejected")
            }

            ModerationAction.HIDE -> review.hiddenAt = review.hiddenAt ?: decision.createdAt

            ModerationAction.RESTORE -> review.hiddenAt = null

            ModerationAction.DISMISS -> reports.dismissAll(TYPE, targetId)

            ModerationAction.RESTRICT_USER -> Unit

            ModerationAction.HIDE_ALL_BY_USER -> hideAllBy(review.author.id, targetId, decision)
        }
        reviews.save(review)
    }

    override fun describe(targetId: UUID, viewerId: UUID): ModerationCaseTarget {
        val revision = revision(targetId)
        val review = revision.review
        val author = review.author
        val history = SubmitterHistory(
            approved = revisions.countByAuthorAndStatus(author.id, ReviewRevisionStatus.APPROVED),
            rejected = revisions.countByAuthorAndStatus(author.id, ReviewRevisionStatus.REJECTED),
            dismissedReports = reportRows.countByReporterIdAndDismissedAtIsNotNull(author.id),
            activeRestrictions = restrictions.activeFor(author.id),
        )
        return TeacherReviewTarget(
            views.revision(revision),
            views.moderated(review),
            views.author(user(viewerId), author),
            reports.activeFor(TYPE, targetId),
            history,
        )
    }

    override fun summaries(targetIds: Collection<UUID>): Map<UUID, CaseTargetSummary> {
        if (targetIds.isEmpty()) return emptyMap()
        return revisions.findAllWithReview(targetIds).associate { revision ->
            val review = revision.review
            revision.id to CaseTargetSummary(
                ownerId = review.author.id,
                review = AdminReviewSummary(
                    review.id,
                    review.teacherIsu,
                    revision.subjectTitle,
                    revision.text.takeCodePoints(EXCERPT),
                    review.score,
                    review.hiddenAt != null,
                    review.anonymous,
                ),
            )
        }
    }

    private fun submit(review: TeacherReviewEntity, now: Instant) {
        withdrawPending(review, now)
        val revision = revisions.save(
            TeacherReviewRevisionEntity(
                review = review,
                number = (revisions.findLatest(review.id)?.number ?: 0) + 1,
                subjectTitle = review.subjectTitle,
                text = review.text,
                status = ReviewRevisionStatus.PENDING,
                submittedAt = now,
            ),
        )
        moderation.openCase(TYPE, revision.id, ModerationCaseReason.SUBMISSION)
    }

    private fun withdrawPending(review: TeacherReviewEntity, now: Instant) {
        val pending = revisions.findPending(review.id) ?: return
        pending.status = ReviewRevisionStatus.WITHDRAWN
        pending.decidedAt = now
        // Flush before a new PENDING insert: Hibernate runs inserts ahead of updates.
        revisions.saveAndFlush(pending)
        moderation.withdraw(TYPE, pending.id)
    }

    private fun decide(revision: TeacherReviewRevisionEntity, status: ReviewRevisionStatus, decision: ModerationDecisionEntity) {
        revision.status = status
        revision.decidedAt = decision.createdAt
        revision.note = decision.note
        revisions.save(revision)
    }

    /** Hides every published review of the author and rejects their pending revisions. */
    private fun hideAllBy(authorId: UUID, initiatingTarget: UUID, decision: ModerationDecisionEntity) {
        val visible = reviews.findAllByAuthorId(authorId).filter { it.hiddenAt == null }
        if (visible.isNotEmpty()) {
            val published = revisions.findLatestApprovedIn(visible.map { it.id }).map { it.review.id }.toSet()
            visible.filter { it.id in published }.forEach { it.hiddenAt = decision.createdAt }
        }
        for (pending in revisions.findPendingByAuthor(authorId)) {
            decide(pending, ReviewRevisionStatus.REJECTED, decision)
            // The initiating case stays open for the moderator's next decision.
            if (pending.id != initiatingTarget) moderation.withdraw(TYPE, pending.id)
        }
    }

    /** The shown revision of another author's review; own reviews and reviews about the viewer are refused. */
    private fun othersReview(viewer: User, review: TeacherReviewEntity): TeacherReviewRevisionEntity {
        if (review.author.id == viewer.id) throw BusinessRuleException("Not allowed on own reviews")
        if (review.teacherIsu == viewer.isu) throw BusinessRuleException("Not allowed on reviews about yourself")
        return views.shown(review) ?: throw NotFoundException("Review not found")
    }

    private fun activeCopy(id: UUID): ExternalTeacherReviewEntity? =
        copies.findById(id).orElse(null)?.takeIf { it.provider == ReviewsSyncStore.PROVIDER && it.removedAt == null }

    private fun kickAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                try {
                    kick.ifAvailable { it.kick() }
                } catch (error: Exception) {
                    LoggerFactory.getLogger(TeacherReviewService::class.java)
                        .warn("Review verification enqueue failed: {}", error.javaClass.simpleName)
                }
            }
        })
    }

    private fun revision(id: UUID): TeacherReviewRevisionEntity =
        revisions.findById(id).orElseThrow { NotFoundException("Review revision not found") }

    private fun user(id: UUID): User = users.findById(id).orElseThrow { NotFoundException("User not found") }

    private data class ReviewContent(val subjectTitle: String?, val text: String, val flowIds: List<Long>)

    /** Lengths are code points, as `char_length` counts them in PostgreSQL. */
    private fun SaveTeacherReviewRequest.content(): ReviewContent {
        val normalized = text.replace("\r\n", "\n").trim()
        val length = normalized.codePointCount(0, normalized.length)
        if (length < MIN_TEXT ||
            length > MAX_TEXT
        ) {
            throw InvalidRequestDataException("Review text must be $MIN_TEXT to $MAX_TEXT characters")
        }
        if (normalized.codePoints().anyMatch { Character.getType(it) == Character.CONTROL.toInt() && it != '\n'.code && it != '\t'.code }) {
            throw InvalidRequestDataException("Review text contains control characters")
        }
        val subject = subjectTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (subject != null && subject.codePointCount(0, subject.length) > MAX_SUBJECT) {
            throw InvalidRequestDataException("Subject title is too long")
        }
        // Jackson does not reject nulls inside a Kotlin List<Long>.
        val candidates: List<Long?> = flowIds
        if (candidates.size > MAX_FLOWS || candidates.any { it == null || it <= 0 }) throw InvalidRequestDataException("Invalid flows")
        return ReviewContent(subject, normalized, flowIds.distinct())
    }

    private fun requireTeacher(isu: Int) {
        if (!ReviewTeachers.isIsu(isu.toLong())) throw InvalidRequestDataException("Invalid teacher ISU")
    }

    private fun String.takeCodePoints(count: Int): String =
        if (codePointCount(0, length) <= count) this else substring(0, offsetByCodePoints(0, count))

    companion object {
        val TYPE = ModerationTargetType.TEACHER_REVIEW
        const val MIN_TEXT = 30
        const val MAX_TEXT = 3000
        const val MAX_SUBJECT = 200
        const val MAX_FLOWS = 50
        const val MAX_LEVEL_TEACHERS = 50
        private const val EXCERPT = 160
        private const val DAY_SECONDS = 86_400L
    }
}
