package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.ExternalTeacherReview
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.model.ReviewProvider
import dev.alllexey.itmowidgets.backend.repositories.PostgreSqlRepositoryTest
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.context.annotation.Import

@Import(TeacherReviewService::class)
class TeacherReviewServiceTest @Autowired constructor(
    private val service: TeacherReviewService,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @Test
    fun `only active reviews of the requested teacher are returned`() {
        val first = review(1)
        val second = review(2)
        review(3).removedAt = NOW
        review(4, teacherIsu = 100002)
        em.flush(); em.clear()

        val response = service.reviews(100001)

        assertEquals(setOf(first.id, second.id), response.external.map { it.id }.toSet())
        assertEquals(100001, response.teacherIsu)
        assertEquals("https://onetwozzzplus.github.io/reviews/#/teacher/100001", response.providerUrl)
    }

    @Test
    fun `reviews use newest first order with exact dates before matching before years`() {
        val undated = review(30)
        val beforeYear = review(20).apply { writtenBeforeYear = 2024 }
        val older = review(10).apply { writtenOn = LocalDate.of(2023, 12, 31) }
        val exact = review(3).apply { writtenOn = LocalDate.of(2024, 1, 1) }
        val sameDateHigherId = review(4).apply { writtenOn = LocalDate.of(2024, 1, 1) }
        val newest = review(1).apply { writtenOn = LocalDate.of(2025, 1, 25) }
        em.flush(); em.clear()

        val response = service.reviews(100001)

        assertEquals(listOf(newest, sameDateHigherId, exact, beforeYear, older, undated).map { it.id },
            response.external.map { it.id })
    }

    @Test
    fun `missing review metadata stays null`() {
        val stored = review(1)
        em.flush(); em.clear()

        val review = service.reviews(100001).external.single()

        assertEquals(ExternalTeacherReview(stored.id, null, null, null, null, null, stored.text), review)
    }

    @Test
    fun `review content and exact date are copied without persistence metadata`() {
        val stored = review(1).apply {
            subjectTitle = "Synthetic subject"
            writtenOn = LocalDate.of(2025, 1, 25)
            sourceTitle = "Synthetic source"
            sourceLink = "https://example.org/review"
        }
        em.flush(); em.clear()

        val review = service.reviews(100001).external.single()

        assertEquals(ExternalTeacherReview(stored.id, "Synthetic subject", LocalDate.of(2025, 1, 25), null,
            "Synthetic source", "https://example.org/review", stored.text), review)
    }

    @Test
    fun `before year metadata is copied without an exact date`() {
        val stored = review(1).apply { writtenBeforeYear = 2024 }
        em.flush(); em.clear()

        val review = service.reviews(100001).external.single()

        assertEquals(ExternalTeacherReview(stored.id, null, null, 2024, null, null, stored.text), review)
    }

    @Test
    fun `an unknown positive isu has an empty review list`() {
        review(1)
        em.flush(); em.clear()

        val response = service.reviews(9999999)

        assertEquals(9999999, response.teacherIsu)
        assertEquals("https://onetwozzzplus.github.io/reviews/#/teacher/9999999", response.providerUrl)
        assertTrue(response.external.isEmpty())
    }

    @Test
    fun `nonpositive isus are rejected`() {
        for (isu in listOf(0, -1)) {
            val exception = assertFailsWith<InvalidRequestDataException> { service.reviews(isu) }
            assertEquals("ISU must be positive", exception.message)
        }
    }

    private fun review(externalId: Long, teacherIsu: Int = 100001) = em.persist(ExternalTeacherReviewEntity(
        provider = ReviewProvider.REVIEWS_WORK_GD, externalId = externalId, teacherIsu = teacherIsu,
        teacherName = "Synthetic teacher", subjectTitle = null, sourceTitle = null, sourceLink = null,
        dateRaw = "", writtenOn = null, writtenBeforeYear = null,
        text = "Synthetic review $externalId", firstSeenAt = NOW.minusSeconds(86_400), lastSeenAt = NOW,
    ))

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-28T09:00:00Z")
    }
}
