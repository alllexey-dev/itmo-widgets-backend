package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReview
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewKind
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

class ReviewOrderTest {
    @Test
    fun `a higher score comes before a newer date`() {
        val old = review("1", score = 2, writtenOn = LocalDate.of(2023, 12, 31))
        val new = review("2", score = 0, writtenOn = LocalDate.of(2026, 9, 1))
        val negative = review("3", score = -1, writtenOn = LocalDate.of(2026, 9, 20))

        assertEquals(listOf(old, new, negative), listOf(negative, new, old).sortedWith(ReviewOrder.RANKED))
    }

    @Test
    fun `equal scores are ordered by date newest first`() {
        val reviews = listOf(
            review("3", writtenOn = LocalDate.of(2023, 12, 31)),
            review("1", writtenOn = LocalDate.of(2025, 1, 25)),
            review("2", writtenOn = LocalDate.of(2024, 6, 15)),
        )

        assertEquals(listOf("1", "2", "3"), reviews.sortedWith(ReviewOrder.RANKED).map { it.text })
    }

    @Test
    fun `a real date comes before the same before year which comes before the previous year`() {
        val reviews = listOf(
            review("3", writtenOn = LocalDate.of(2023, 12, 31)),
            review("2", writtenBeforeYear = 2024, kind = TeacherReviewKind.REVIEWS),
            review("1", writtenOn = LocalDate.of(2024, 1, 1), kind = TeacherReviewKind.REVIEWS),
        )

        assertEquals(listOf("1", "2", "3"), reviews.sortedWith(ReviewOrder.RANKED).map { it.text })
    }

    @Test
    fun `undated reviews follow dated ones`() {
        val reviews = listOf(
            review("3", kind = TeacherReviewKind.REVIEWS),
            review("1", writtenOn = LocalDate.of(2025, 1, 25)),
            review("2", writtenBeforeYear = 2024, kind = TeacherReviewKind.REVIEWS),
        )

        assertEquals(listOf("1", "2", "3"), reviews.sortedWith(ReviewOrder.RANKED).map { it.text })
    }

    @Test
    fun `own reviews come before copies with equal keys`() {
        val date = LocalDate.of(2025, 1, 25)
        val copy = review("copy", writtenOn = date, kind = TeacherReviewKind.REVIEWS, id = UUID(0, 1))
        val own = review("own", writtenOn = date, id = UUID(0, 2))

        assertEquals(listOf(own, copy), listOf(copy, own).sortedWith(ReviewOrder.RANKED))
    }

    @Test
    fun `the id as a string breaks the remaining ties`() {
        val date = LocalDate.of(2025, 1, 25)
        val ids = listOf(
            "b0000000-0000-0000-0000-000000000000",
            "a0000000-0000-0000-0000-000000000000",
            "10000000-0000-0000-0000-000000000000",
        ).map(UUID::fromString)
        val reviews = ids.map { review(it.toString(), writtenOn = date, id = it) }

        assertEquals(ids.map { it.toString() }.sorted(), reviews.sortedWith(ReviewOrder.RANKED).map { it.id.toString() })
    }

    private fun review(
        text: String,
        score: Int = 0,
        writtenOn: LocalDate? = null,
        writtenBeforeYear: Int? = null,
        kind: TeacherReviewKind = TeacherReviewKind.COMMUNITY,
        id: UUID = UUID.randomUUID(),
    ) = TeacherReview(
        id, kind, null, writtenOn, writtenBeforeYear, text, score, myVote = 0, verified = false,
        reportedByMe = false, author = null, sourceTitle = null, sourceLink = null,
    )
}
