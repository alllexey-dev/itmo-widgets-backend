package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.ExternalReviewSyncStateEntity
import dev.alllexey.itmowidgets.backend.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.model.ReviewProvider
import dev.alllexey.itmowidgets.backend.model.ReviewSyncOutcome
import dev.alllexey.itmowidgets.backend.repositories.ExternalReviewSyncStateRepository
import dev.alllexey.itmowidgets.backend.repositories.ExternalTeacherReviewRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Every teacher the provider still knows, in registry order, fetched completely before anything is written. */
data class ReviewsSnapshot(val etag: String?, val teachers: List<ReviewsTeacher>)

data class ReviewsSyncResult(val added: Int, val updated: Int, val removed: Int, val teachers: Int, val reviews: Int)

/** Each call commits on its own; the sync itself runs HTTP outside any transaction. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class ReviewsSyncStore(
    private val reviews: ExternalTeacherReviewRepository,
    private val states: ExternalReviewSyncStateRepository,
    private val audit: AdminAuditService,
) {
    fun claim(now: Instant): Boolean = states.claim(PROVIDER, now, now.minus(STALE_LEASE)) == 1

    /** The audit row is written only by the admin who actually started the run. */
    fun claimManual(adminId: UUID, now: Instant): Boolean {
        if (!claim(now)) return false
        audit.record(adminId, AdminAuditAction.REVIEWS_SYNC_STARTED, AUDIT_TARGET, null)
        return true
    }

    fun release() {
        states.release(PROVIDER)
    }

    fun state(): ExternalReviewSyncStateEntity = states.findById(PROVIDER).orElseThrow()

    fun recordUnchanged(now: Instant) {
        val state = state()
        state.lastCheckedAt = now
        state.lastSuccessAt = now
        state.lastOutcome = ReviewSyncOutcome.UNCHANGED
        state.lastError = null
        state.runningSince = null
    }

    /** Keeps the ETag and every review, so the next run fetches the full snapshot again. */
    fun recordFailure(now: Instant, summary: String) {
        val state = state()
        state.lastCheckedAt = now
        state.lastOutcome = ReviewSyncOutcome.FAILED
        state.lastError = summary.take(ERROR_LENGTH)
        state.runningSince = null
    }

    fun applySnapshot(snapshot: ReviewsSnapshot, now: Instant): ReviewsSyncResult {
        states.lockProvider(PROVIDER.name)
        val existing = reviews.findAllByProvider(PROVIDER).associateBy { it.externalId }
        val seen = mutableSetOf<Long>()
        var added = 0
        var updated = 0
        for (teacher in snapshot.teachers) {
            for (comment in teacher.comments) {
                // The first teacher listing a review keeps it.
                if (!seen.add(comment.id)) continue
                val row = existing[comment.id]
                if (row == null) {
                    reviews.save(newReview(teacher, comment, now))
                    added++
                } else {
                    if (refresh(row, teacher, comment) || row.removedAt != null) updated++
                    row.lastSeenAt = now
                    row.removedAt = null
                }
            }
        }
        var removed = 0
        for (row in existing.values) {
            if (row.externalId !in seen && row.removedAt == null) {
                row.removedAt = now
                removed++
            }
        }
        val result = ReviewsSyncResult(added, updated, removed, snapshot.teachers.size, seen.size)
        val state = state()
        state.etag = snapshot.etag
        state.lastCheckedAt = now
        state.lastChangedAt = now
        state.lastSuccessAt = now
        state.lastOutcome = ReviewSyncOutcome.UPDATED
        state.lastError = null
        state.lastAdded = result.added
        state.lastUpdated = result.updated
        state.lastRemoved = result.removed
        state.teachersTotal = result.teachers
        state.reviewsTotal = result.reviews
        state.runningSince = null
        return result
    }

    private fun newReview(teacher: ReviewsTeacher, comment: ReviewsComment, now: Instant): ExternalTeacherReviewEntity {
        val date = ReviewDates.parse(comment.dateRaw)
        return ExternalTeacherReviewEntity(
            provider = PROVIDER,
            externalId = comment.id,
            teacherIsu = teacher.id.toInt(),
            teacherName = teacher.name,
            subjectTitle = comment.subjectTitle,
            sourceTitle = comment.sourceTitle,
            sourceLink = comment.sourceLink,
            dateRaw = comment.dateRaw,
            writtenOn = date.writtenOn,
            writtenBeforeYear = date.writtenBeforeYear,
            text = comment.text,
            firstSeenAt = now,
            lastSeenAt = now,
        )
    }

    /** Returns whether the content changed; timestamps are the caller's. */
    private fun refresh(row: ExternalTeacherReviewEntity, teacher: ReviewsTeacher, comment: ReviewsComment): Boolean {
        val date = ReviewDates.parse(comment.dateRaw)
        val changed = row.text != comment.text || row.teacherIsu.toLong() != teacher.id || row.teacherName != teacher.name ||
            row.subjectTitle != comment.subjectTitle || row.sourceTitle != comment.sourceTitle ||
            row.sourceLink != comment.sourceLink || row.dateRaw != comment.dateRaw ||
            row.writtenOn != date.writtenOn || row.writtenBeforeYear != date.writtenBeforeYear
        if (changed) {
            row.text = comment.text
            row.teacherIsu = teacher.id.toInt()
            row.teacherName = teacher.name
            row.subjectTitle = comment.subjectTitle
            row.sourceTitle = comment.sourceTitle
            row.sourceLink = comment.sourceLink
            row.dateRaw = comment.dateRaw
            row.writtenOn = date.writtenOn
            row.writtenBeforeYear = date.writtenBeforeYear
        }
        return changed
    }

    companion object {
        val PROVIDER = ReviewProvider.REVIEWS_WORK_GD
        val STALE_LEASE: Duration = Duration.ofHours(6)
        const val AUDIT_TARGET = "reviews-sync"
        private const val ERROR_LENGTH = 300
    }
}
