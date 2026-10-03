package dev.alllexey.itmowidgets.backend.feature.reviews.persistence

import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewProvider
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager

class ExternalTeacherReviewPersistenceTest @Autowired constructor(
    private val em: TestEntityManager,
    private val reviews: ExternalTeacherReviewRepository,
    private val states: ExternalReviewSyncStateRepository,
) : PostgreSqlRepositoryTest() {
    private val now = Instant.parse("2026-09-24T02:00:00Z")

    @Test
    fun `counters separate active and removed reviews and count only active teachers`() {
        review(1, teacherIsu = 100001)
        review(2, teacherIsu = 100001)
        review(3, teacherIsu = 100002)
        review(4, teacherIsu = 100003, removedAt = now)
        em.flush(); em.clear()

        val provider = ReviewProvider.REVIEWS_WORK_GD
        assertEquals(4, reviews.countByProvider(provider))
        assertEquals(3, reviews.countByProviderAndRemovedAtIsNull(provider))
        assertEquals(1, reviews.countByProviderAndRemovedAtIsNotNull(provider))
        assertEquals(2, reviews.countActiveTeachers(provider))
        assertEquals(listOf(1L, 2L, 3L, 4L), reviews.findAllByProvider(provider).map { it.externalId }.sorted())
        val stored = reviews.findAllByProvider(provider).single { it.externalId == 1L }
        assertEquals(LocalDate.of(2025, 1, 25), stored.writtenOn)
        assertNull(stored.writtenBeforeYear)
        assertNull(stored.subjectTitle)
    }

    @Test
    fun `teacher reviews contain only the requested teachers active rows`() {
        review(1, teacherIsu = 100001)
        review(2, teacherIsu = 100001)
        review(3, teacherIsu = 100001, removedAt = now)
        review(4, teacherIsu = 100002)
        em.flush(); em.clear()

        val stored = reviews.findAllByProviderAndTeacherIsuAndRemovedAtIsNull(ReviewProvider.REVIEWS_WORK_GD, 100001)

        assertEquals(listOf(1L, 2L), stored.map { it.externalId }.sorted())
    }

    @Test
    fun `one caller wins the lease until it is released or goes stale`() {
        val provider = ReviewProvider.REVIEWS_WORK_GD
        val staleBefore = now.minusSeconds(6 * 3_600)

        assertEquals(1, states.claim(provider, now, staleBefore))
        assertEquals(0, states.claim(provider, now.plusSeconds(60), staleBefore.plusSeconds(60)))
        assertEquals(now, states.findById(provider).orElseThrow().runningSince)

        val later = now.plusSeconds(7 * 3_600)
        assertEquals(1, states.claim(provider, later, later.minusSeconds(6 * 3_600)))
        assertEquals(later, states.findById(provider).orElseThrow().runningSince)

        assertEquals(1, states.release(provider))
        assertEquals(0, states.release(provider))
        assertNull(states.findById(provider).orElseThrow().runningSince)
        assertEquals(1, states.claim(provider, later, later.minusSeconds(6 * 3_600)))
        assertEquals(1, states.lockProvider(provider.name))
    }

    private fun review(externalId: Long, teacherIsu: Int, removedAt: Instant? = null) = em.persist(ExternalTeacherReviewEntity(
        provider = ReviewProvider.REVIEWS_WORK_GD, externalId = externalId, teacherIsu = teacherIsu,
        teacherName = "Synthetic teacher", subjectTitle = null, sourceTitle = "Synthetic source", sourceLink = null,
        dateRaw = "12:18 25.01.2025", writtenOn = LocalDate.of(2025, 1, 25), writtenBeforeYear = null,
        text = "Synthetic review", firstSeenAt = now, lastSeenAt = now, removedAt = removedAt,
    ))
}
