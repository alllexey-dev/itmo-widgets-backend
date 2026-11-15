package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewStatsDay
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ReviewStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The review aggregates on real PostgreSQL with synthetic reviews and votes in 2032, a year no other test writes to;
 * `ownReviews` is the state now and is compared as a delta. Moscow is UTC+3: its day starts at 21:00 UTC.
 */
@Import(ReviewStatistics::class, ReviewStatisticsRepository::class, ReviewUsage::class, ReviewVoteUsage::class)
class ReviewStatisticsTest @Autowired constructor(
    private val statistics: ReviewStatistics,
    private val reviewUsage: ReviewUsage,
    private val voteUsage: ReviewVoteUsage,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    private val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-03"))
    private var nextIsu = 984_200
    private var nextTeacher = 984_200
    private var nextExternal = 984_200L

    @Test
    fun `an empty period has a zero day for every day and no use`() {
        val empty = StatsPeriod(LocalDate.parse("2032-07-01"), LocalDate.parse("2032-07-02"))

        assertEquals(empty.days.map { ReviewStatsDay(it, 0, 0, 0) }, statistics.stats(empty).days)
        assertEquals(FeatureUsage(0, emptySet()), reviewUsage.usage(empty))
        assertEquals(FeatureUsage(0, emptySet()), voteUsage.usage(empty))
    }

    @Test
    fun `reviews and both kinds of votes count by the Moscow day they were created, every review counts now`() {
        val before = statistics.stats(period).ownReviews
        val (first, second, third) = List(3) { user() }
        val early = review(first, "2032-02-29T20:59:59Z")
        val midnight = review(second, "2032-02-29T21:00:00Z")
        review(first, "2032-03-03T20:59:59Z", hidden = true)
        review(third, "2032-03-03T21:00:00Z")
        vote(early, third, "2032-03-01T10:00:00Z")
        vote(midnight, first, "2032-03-02T10:00:00Z")
        vote(midnight, third, "2032-03-04T10:00:00Z")
        val copy = external()
        val otherCopy = external()
        externalVote(copy, first, "2032-03-02T21:00:00Z")
        externalVote(otherCopy, first, "2032-03-02T22:00:00Z")
        externalVote(copy, second, "2032-02-28T10:00:00Z")

        val stats = statistics.stats(period)

        assertEquals(4, stats.ownReviews - before)
        assertEquals(
            listOf(
                ReviewStatsDay(LocalDate.parse("2032-03-01"), newReviews = 1, reviewVotes = 1, externalReviewVotes = 0),
                ReviewStatsDay(LocalDate.parse("2032-03-02"), newReviews = 0, reviewVotes = 1, externalReviewVotes = 0),
                ReviewStatsDay(LocalDate.parse("2032-03-03"), newReviews = 1, reviewVotes = 0, externalReviewVotes = 2),
            ),
            stats.days,
        )
        assertEquals(FeatureUsage(2, setOf(first, second)), reviewUsage.usage(period))
        // Votes of both kinds by the same voter count once as a user.
        assertEquals(FeatureUsage(4, setOf(first, third)), voteUsage.usage(period))
        assertEquals(listOf("REVIEWS", "REVIEW_VOTES"), listOf(reviewUsage.feature, voteUsage.feature))
    }

    private fun user(): UUID = jdbc.insertUser(nextIsu++, createdAt = Instant.parse("2032-01-01T00:00:00Z"))

    private fun review(author: UUID, created: String, hidden: Boolean = false): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO teacher_reviews (id, author_id, teacher_isu, text, verification, hidden_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'UNVERIFIED', ?, ?, ?)
            """,
            id,
            author,
            nextTeacher++,
            "A synthetic review long enough for the check.",
            if (hidden) at(created) else null,
            at(created),
            at(created),
        )
        return id
    }

    private fun external(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO external_teacher_reviews (id, provider, external_id, teacher_isu, teacher_name, date_raw, text,
                first_seen_at, last_seen_at, score)
            VALUES (?, 'REVIEWS_WORK_GD', ?, ?, 'Synthetic teacher', '2031', 'Synthetic text', ?, ?, 0)
            """,
            id,
            nextExternal++,
            nextTeacher++,
            at("2032-01-01T00:00:00Z"),
            at("2032-01-01T00:00:00Z"),
        )
        return id
    }

    private fun vote(review: UUID, user: UUID, created: String) {
        jdbc.update(
            "INSERT INTO teacher_review_votes (review_id, user_id, value, created_at) VALUES (?, ?, 1, ?)",
            review,
            user,
            at(created),
        )
    }

    private fun externalVote(review: UUID, user: UUID, created: String) {
        jdbc.update(
            "INSERT INTO external_teacher_review_votes (review_id, user_id, value, created_at) VALUES (?, ?, -1, ?)",
            review,
            user,
            at(created),
        )
    }

    private fun at(text: String): Timestamp = Timestamp.from(Instant.parse(text))
}
