package dev.alllexey.itmowidgets.backend.feature.reviews.persistence

import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewRevisionStatus
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewVerification
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewRevisionEntity
import dev.alllexey.itmowidgets.backend.feature.schedule.model.LessonEntity
import dev.alllexey.itmowidgets.backend.feature.schedule.model.UserSubjectFlowEntity
import dev.alllexey.itmowidgets.backend.feature.schedule.model.UserSubjectFlowId
import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.LessonRepository
import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.UserSubjectFlowRepository
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.testing.persistUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.data.domain.Limit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.*

class TeacherReviewPersistenceTest @Autowired constructor(
    private val em: TestEntityManager,
    private val reviews: TeacherReviewRepository,
    private val revisions: TeacherReviewRevisionRepository,
    private val lessons: LessonRepository,
    private val flows: UserSubjectFlowRepository,
) : PostgreSqlRepositoryTest() {
    private val now = Instant.parse("2026-09-29T09:00:00Z")

    @Test
    fun `lockByAuthorAndTeacher finds only the author's own review`() {
        val author = em.persistUser(964001, createdAt = now)
        val other = em.persistUser(964002, createdAt = now)
        val review = review(author, TEACHER)
        review(other, OTHER_TEACHER)
        em.flush()
        em.clear()

        assertEquals(review.id, reviews.lockByAuthorAndTeacher(author.id, TEACHER))
        assertNull(reviews.lockByAuthorAndTeacher(other.id, TEACHER))
        assertNull(reviews.lockByAuthorAndTeacher(author.id, OTHER_TEACHER))
        assertEquals(review.id, reviews.lockById(review.id))
        assertNull(reviews.lockById(UUID.randomUUID()))
        assertEquals(review.id, reviews.findByAuthorIdAndTeacherIsu(author.id, TEACHER)?.id)
    }

    @Test
    fun `findDue returns only pending reviews whose check is due, the longest waiting first`() {
        val later = review(em.persistUser(964011, createdAt = now), TEACHER, dueAt = now.minusSeconds(60))
        val earlier = review(em.persistUser(964012, createdAt = now), TEACHER, dueAt = now.minusSeconds(3_600))
        val exactlyNow = review(em.persistUser(964013, createdAt = now), TEACHER, dueAt = now)
        review(em.persistUser(964014, createdAt = now), TEACHER, dueAt = now.plusSeconds(1))
        review(em.persistUser(964015, createdAt = now), TEACHER, verification = ReviewVerification.VERIFIED, verifiedFlowId = 93724)
        review(em.persistUser(964016, createdAt = now), TEACHER, verification = ReviewVerification.UNVERIFIED)
        em.flush()
        em.clear()

        val due = reviews.findDue(now, Limit.of(10)).filter { it.teacherIsu == TEACHER }

        assertEquals(listOf(earlier.id, later.id, exactlyNow.id), due.map { it.id })
        assertEquals(listOf(earlier.id), reviews.findDue(now, Limit.of(1)).filter { it.teacherIsu == TEACHER }.map { it.id })
    }

    @Test
    fun `existsPublishedForTeacher needs an approved revision of a review that is not hidden`() {
        val pending = review(em.persistUser(964021, createdAt = now), TEACHER)
        revision(pending, 1, ReviewRevisionStatus.PENDING)
        val rejected = review(em.persistUser(964022, createdAt = now), TEACHER)
        revision(rejected, 1, ReviewRevisionStatus.REJECTED)
        val hidden = review(em.persistUser(964023, createdAt = now), OTHER_TEACHER, hiddenAt = now)
        revision(hidden, 1, ReviewRevisionStatus.APPROVED)
        em.flush()
        em.clear()

        assertFalse(reviews.existsPublishedForTeacher(TEACHER))
        assertFalse(reviews.existsPublishedForTeacher(OTHER_TEACHER))

        revision(reviews.findById(rejected.id).orElseThrow(), 2, ReviewRevisionStatus.APPROVED)
        em.flush()
        em.clear()

        assertTrue(reviews.existsPublishedForTeacher(TEACHER))
    }

    @Test
    fun `revisions resolve latest content and count the author's submissions since a moment`() {
        val author = em.persistUser(964031, createdAt = now)
        val review = review(author, TEACHER)
        revision(review, 1, ReviewRevisionStatus.APPROVED, submittedAt = now.minusSeconds(2 * 86_400))
        val approved = revision(review, 2, ReviewRevisionStatus.APPROVED, submittedAt = now.minusSeconds(3_600))
        val pending = revision(review, 3, ReviewRevisionStatus.PENDING, submittedAt = now)
        val otherReview = review(author, OTHER_TEACHER)
        revision(otherReview, 1, ReviewRevisionStatus.REJECTED, submittedAt = now.minusSeconds(60))
        revision(review(em.persistUser(964032, createdAt = now), TEACHER), 1, ReviewRevisionStatus.PENDING, submittedAt = now)
        em.flush()
        em.clear()

        assertEquals(pending.id, revisions.findLatest(review.id)?.id)
        assertEquals(approved.id, revisions.findLatestApproved(review.id)?.id)
        assertEquals(pending.id, revisions.findPending(review.id)?.id)
        assertNull(revisions.findLatestApproved(otherReview.id))
        assertEquals(
            setOf(pending.id, revisions.findLatest(otherReview.id)?.id),
            revisions.findLatestIn(listOf(review.id, otherReview.id))
                .map { it.id }.toSet(),
        )
        assertEquals(listOf(approved.id), revisions.findLatestApprovedIn(listOf(review.id, otherReview.id)).map { it.id })
        assertEquals(listOf(1, 2, 3), revisions.findAllByReview(review.id).map { it.number })
        assertEquals(listOf(pending.id), revisions.findPendingByAuthor(author.id).map { it.id })
        assertEquals(4, revisions.countByAuthorSince(author.id, now.minusSeconds(3 * 86_400)))
        assertEquals(3, revisions.countByAuthorSince(author.id, now.minusSeconds(3_600)))
        assertEquals(1, revisions.countByAuthorSince(author.id, now))
        assertEquals(0, revisions.countByAuthorSince(author.id, now.plusSeconds(1)))
        assertEquals(2, revisions.countByAuthorAndStatus(author.id, ReviewRevisionStatus.APPROVED))
    }

    @Test
    fun `academic lessons tell whether a teacher is known and which flows of the user they taught`() {
        val teacher = 9_640_001L
        lesson(964041, 1, LocalDate.of(2025, 10, 1), flowId = 501, teacherIsu = teacher)
        lesson(964041, 2, LocalDate.of(2026, 9, 20), flowId = 502, teacherIsu = teacher)
        lesson(964041, 3, LocalDate.of(2026, 9, 21), flowId = 501, teacherIsu = teacher)
        lesson(964041, 4, LocalDate.of(2026, 9, 22), flowId = 503, teacherIsu = teacher + 1)
        lesson(964042, 5, LocalDate.of(2026, 9, 25), flowId = 504, teacherIsu = teacher)
        // A room booking (flow type 5) names the person who booked it; that alone is not teaching.
        lesson(964041, 6, LocalDate.of(2026, 9, 26), flowId = 505, teacherIsu = teacher + 3, flowTypeId = 5)
        lesson(964041, 7, LocalDate.of(2026, 9, 27), flowId = 506, teacherIsu = teacher, flowTypeId = 5)
        em.flush()
        em.clear()

        assertTrue(lessons.existsTeacher(teacher))
        assertTrue(lessons.existsTeacher(teacher + 1))
        assertFalse(lessons.existsTeacher(teacher + 2))
        assertFalse(lessons.existsTeacher(teacher + 3))
        assertEquals(listOf(501L, 502L), lessons.findTeacherFlows(964041, teacher))
        assertEquals(listOf(504L), lessons.findTeacherFlows(964042, teacher))
        assertEquals(emptyList(), lessons.findTeacherFlows(964042, teacher + 1))
    }

    @Test
    fun `recent flows of a user are distinct, newest seen first and limited`() {
        val user = em.persistUser(964051, createdAt = now)
        flow(user, subjectId = 1, periodKey = "2025-1", flowId = 601, lastSeen = LocalDate.of(2025, 12, 1))
        flow(user, subjectId = 2, periodKey = "2025-1", flowId = 601, lastSeen = LocalDate.of(2026, 9, 1))
        flow(user, subjectId = 3, periodKey = "2026-1", flowId = 602, lastSeen = LocalDate.of(2026, 9, 20))
        flow(user, subjectId = 4, periodKey = "2025-2", flowId = 603, lastSeen = LocalDate.of(2026, 5, 1))
        flow(
            em.persistUser(964052, createdAt = now),
            subjectId = 1,
            periodKey = "2026-1",
            flowId = 604,
            lastSeen = LocalDate.of(2026, 9, 28),
        )
        em.flush()
        em.clear()

        assertEquals(listOf(602L, 601L, 603L), flows.findRecentFlowIds(user.id, Limit.of(10)))
        assertEquals(listOf(602L, 601L), flows.findRecentFlowIds(user.id, Limit.of(2)))
    }

    private fun review(
        author: User,
        teacherIsu: Int,
        verification: ReviewVerification = ReviewVerification.PENDING,
        dueAt: Instant? = now.plus(1, ChronoUnit.DAYS),
        verifiedFlowId: Long? = null,
        hiddenAt: Instant? = null,
    ) = em.persist(
        TeacherReviewEntity(
            author = author, teacherIsu = teacherIsu, subjectTitle = "Синтетический предмет", text = TEXT, hiddenAt = hiddenAt,
            verification = verification, verifiedFlowId = verifiedFlowId,
            verificationDueAt = dueAt.takeIf { verification == ReviewVerification.PENDING }, createdAt = now, updatedAt = now,
        ),
    )

    private fun revision(review: TeacherReviewEntity, number: Int, status: ReviewRevisionStatus, submittedAt: Instant = now) = em.persist(
        TeacherReviewRevisionEntity(
            review = review,
            number = number,
            subjectTitle = review.subjectTitle,
            text = TEXT,
            status = status,
            submittedAt = submittedAt,
            decidedAt = submittedAt.takeIf { status != ReviewRevisionStatus.PENDING },
        ),
    )

    private fun lesson(userIsu: Int, pairId: Long, date: LocalDate, flowId: Long, teacherIsu: Long, flowTypeId: Int = 2) = em.persist(
        LessonEntity(
            userIsu = userIsu, date = date, pairId = 9_640_000L + pairId, subjectId = 1, subjectName = "Synthetic subject",
            teacherIsu = teacherIsu, teacherFio = null, start = LocalTime.of(10, 0), end = LocalTime.of(11, 30), type = "Лекция",
            typeId = 1, groupName = "M3100", flowId = flowId, flowTypeId = flowTypeId, note = null, room = null, building = null,
            buildingId = null, mainBuildingId = null, format = "Очно", formatId = 1,
        ),
    )

    private fun flow(user: User, subjectId: Long, periodKey: String, flowId: Long, lastSeen: LocalDate) = em.persist(
        UserSubjectFlowEntity(
            UserSubjectFlowId(user.id, subjectId, periodKey, flowId),
            groupName = "M3100",
            typeId = 2,
            lastSeen = lastSeen,
        ),
    )

    private companion object {
        const val TEACHER = 964900
        const val OTHER_TEACHER = 964901
        const val TEXT = "Синтетический отзыв о преподавателе для теста"
    }
}
