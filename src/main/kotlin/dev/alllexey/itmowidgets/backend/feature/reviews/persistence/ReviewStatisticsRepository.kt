package dev.alllexey.itmowidgets.backend.feature.reviews.persistence

import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.queryDays
import dev.alllexey.itmowidgets.backend.platform.stats.queryUsage
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Aggregates over own teacher reviews and the votes on own reviews and on Reviews copies, for statistics. Every
 * method is one query and returns counts; a daily series has no row for a day without any.
 */
@Repository
class ReviewStatisticsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** Every own teacher review now, hidden ones included. */
    fun ownReviews(): Long = jdbc.queryForObject("SELECT COUNT(*) FROM teacher_reviews", emptyMap<String, Any>(), Long::class.java)!!

    /** Own reviews created per Europe/Moscow day. */
    fun reviewsPerDay(period: StatsPeriod): List<DayCount> = jdbc.queryDays(perDay("teacher_reviews"), period)

    /** Votes on own reviews per Europe/Moscow day of their first casting. */
    fun ownReviewVotesPerDay(period: StatsPeriod): List<DayCount> = jdbc.queryDays(perDay("teacher_review_votes"), period)

    /** Votes on Reviews copies per Europe/Moscow day of their first casting. */
    fun externalReviewVotesPerDay(period: StatsPeriod): List<DayCount> = jdbc.queryDays(perDay("external_teacher_review_votes"), period)

    /** Own reviews created in the period and their authors. */
    fun reviewUsage(period: StatsPeriod): FeatureUsage = jdbc.queryUsage(REVIEW_USAGE, period)

    /** Votes of both kinds cast in the period and their voters. */
    fun voteUsage(period: StatsPeriod): FeatureUsage = jdbc.queryUsage(VOTE_USAGE, period)

    private fun perDay(table: String) = """
        SELECT CAST(created_at AT TIME ZONE :zone AS date) AS day, COUNT(*) AS count FROM $table
        WHERE created_at >= :start AND created_at < :end
        GROUP BY 1
    """

    private companion object {
        const val REVIEW_USAGE = """
            SELECT author_id AS user_id, COUNT(*) AS events FROM teacher_reviews
            WHERE created_at >= :start AND created_at < :end GROUP BY author_id
        """

        const val VOTE_USAGE = """
            SELECT user_id, COUNT(*) AS events FROM (
                SELECT user_id FROM teacher_review_votes WHERE created_at >= :start AND created_at < :end
                UNION ALL
                SELECT user_id FROM external_teacher_review_votes WHERE created_at >= :start AND created_at < :end
            ) votes
            GROUP BY user_id
        """
    }
}
