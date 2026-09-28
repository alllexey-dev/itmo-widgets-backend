package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.model.ReviewProvider
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ReviewOrderTest {
    private val now = Instant.parse("2026-09-24T02:00:00Z")

    @Test
    fun `real dates are ordered newest first`() {
        val reviews = listOf(
            review(3, writtenOn = LocalDate.of(2023, 12, 31)),
            review(1, writtenOn = LocalDate.of(2025, 1, 25)),
            review(2, writtenOn = LocalDate.of(2024, 6, 15)),
        )

        assertEquals(listOf(1L, 2L, 3L), reviews.sortedWith(ReviewOrder.NEWEST_FIRST).map { it.externalId })
    }

    @Test
    fun `before year is between the first day of that year and the last day of the previous year`() {
        val reviews = listOf(
            review(3, writtenBeforeYear = 2024),
            review(2, writtenOn = LocalDate.of(2023, 12, 31)),
            review(1, writtenOn = LocalDate.of(2024, 1, 1)),
        )

        assertEquals(listOf(1L, 3L, 2L), reviews.sortedWith(ReviewOrder.NEWEST_FIRST).map { it.externalId })
    }

    @Test
    fun `reviews without a date follow real dates and before years`() {
        val reviews = listOf(
            review(3),
            review(1, writtenOn = LocalDate.of(2025, 1, 25)),
            review(2, writtenBeforeYear = 2024),
        )

        assertEquals(listOf(1L, 2L, 3L), reviews.sortedWith(ReviewOrder.NEWEST_FIRST).map { it.externalId })
    }

    @Test
    fun `equal real dates are ordered by external id descending`() {
        val date = LocalDate.of(2025, 1, 25)
        val reviews = listOf(
            review(2, writtenOn = date),
            review(1, writtenOn = date),
            review(3, writtenOn = date),
        )

        assertEquals(listOf(3L, 2L, 1L), reviews.sortedWith(ReviewOrder.NEWEST_FIRST).map { it.externalId })
    }

    @Test
    fun `equal before years are ordered by external id descending`() {
        val reviews = listOf(
            review(2, writtenBeforeYear = 2024),
            review(1, writtenBeforeYear = 2024),
            review(3, writtenBeforeYear = 2024),
        )

        assertEquals(listOf(3L, 2L, 1L), reviews.sortedWith(ReviewOrder.NEWEST_FIRST).map { it.externalId })
    }

    @Test
    fun `missing dates are ordered by external id descending`() {
        val reviews = listOf(review(2), review(1), review(3))

        assertEquals(listOf(3L, 2L, 1L), reviews.sortedWith(ReviewOrder.NEWEST_FIRST).map { it.externalId })
    }

    private fun review(
        externalId: Long,
        writtenOn: LocalDate? = null,
        writtenBeforeYear: Int? = null,
    ) = ExternalTeacherReviewEntity(
        provider = ReviewProvider.REVIEWS_WORK_GD, externalId = externalId, teacherIsu = 100001,
        teacherName = "Synthetic teacher", subjectTitle = null, sourceTitle = null, sourceLink = null,
        dateRaw = "", writtenOn = writtenOn, writtenBeforeYear = writtenBeforeYear,
        text = "Synthetic review", firstSeenAt = now, lastSeenAt = now,
    )
}
