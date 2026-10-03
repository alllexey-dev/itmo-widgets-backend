package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewFlowRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.LessonRepository
import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.UserSubjectFlowRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** A due ISU check; [dueAt] identifies the check, so a save by the author meanwhile is never overwritten. */
data class VerificationTask(
    val reviewId: UUID,
    val authorId: UUID,
    val authorIsu: Int,
    val teacherIsu: Int,
    val dueAt: Instant,
    val attempts: Int,
)

/** The verification queue in `teacher_reviews`. Every call commits on its own. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class ReviewVerificationStore(
    private val reviews: TeacherReviewRepository,
    private val flows: TeacherReviewFlowRepository,
    private val lessons: LessonRepository,
    private val subjectFlows: UserSubjectFlowRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun due(now: Instant, limit: Int): List<VerificationTask> = reviews.findDue(now, Limit.of(limit)).map {
        VerificationTask(it.id, it.author.id, it.author.isu, it.teacherIsu, checkNotNull(it.verificationDueAt), it.verificationAttempts)
    }

    /**
     * Flows to check, without repeats and at most [MAX_CANDIDATES]: the author's loaded lessons with the teacher,
     * then the flows sent with the latest save, then the author's schedule flows, the most recently seen first.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun candidates(task: VerificationTask): List<Long> = (
        lessons.findTeacherFlows(task.authorIsu, task.teacherIsu.toLong()).asSequence() +
            flows.findFlowIds(task.reviewId).asSequence() +
            subjectFlows.findRecentFlowIds(task.authorId, Limit.of(MAX_CANDIDATES)).asSequence()
        )
        .distinct().take(MAX_CANDIDATES).toList()

    fun markVerified(task: VerificationTask, flowId: Long, now: Instant): Boolean =
        reviews.markVerified(task.reviewId, task.dueAt, flowId, now) == 1

    fun markUnverified(task: VerificationTask, now: Instant): Boolean = reviews.markUnverified(task.reviewId, task.dueAt, now) == 1

    fun postpone(task: VerificationTask, until: Instant, countAttempt: Boolean): Boolean =
        reviews.postpone(task.reviewId, task.dueAt, until, if (countAttempt) 1 else 0) == 1

    fun postponeAllDue(now: Instant, until: Instant): Int = reviews.postponeAllDue(now, until)

    fun rescheduleAllPending(now: Instant): Int = reviews.rescheduleAllPending(now)

    companion object {
        const val MAX_CANDIDATES = 40
    }
}
