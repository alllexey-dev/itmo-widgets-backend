package dev.alllexey.itmowidgets.backend.feature.social.persistence

import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.queryDays
import dev.alllexey.itmowidgets.backend.platform.stats.queryUsage
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Aggregates over friendships for statistics. Every method is one query and returns counts; a daily series has no
 * row for a day without any.
 */
@Repository
class SocialStatisticsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** Friendship rows created (requests sent) per Europe/Moscow day. */
    fun requestsPerDay(period: StatsPeriod): List<DayCount> = jdbc.queryDays(
        """
        SELECT CAST(created_at AT TIME ZONE :zone AS date) AS day, COUNT(*) AS count FROM friendships
        WHERE created_at >= :start AND created_at < :end
        GROUP BY 1
        """,
        period,
    )

    /** Friendships accepted per Europe/Moscow day; only an accepted row has `responded_at`. */
    fun acceptsPerDay(period: StatsPeriod): List<DayCount> = jdbc.queryDays(
        """
        SELECT CAST(responded_at AT TIME ZONE :zone AS date) AS day, COUNT(*) AS count FROM friendships
        WHERE responded_at >= :start AND responded_at < :end
        GROUP BY 1
        """,
        period,
    )

    /** Users with at least one accepted friendship now. */
    fun usersWithFriends(): Long = jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM (
            SELECT requester_id FROM friendships WHERE status = 'ACCEPTED'
            UNION
            SELECT addressee_id FROM friendships WHERE status = 'ACCEPTED'
        ) befriended
        """,
        emptyMap<String, Any>(),
        Long::class.java,
    )!!

    /**
     * Requests sent in the period, counted for their requester, and both sides of every row sent or accepted in the
     * period as its users.
     */
    fun friendUsage(period: StatsPeriod): FeatureUsage = jdbc.queryUsage(
        """
        WITH touched AS (
            SELECT requester_id, addressee_id, created_at >= :start AND created_at < :end AS sent FROM friendships
            WHERE (created_at >= :start AND created_at < :end) OR (responded_at >= :start AND responded_at < :end)
        )
        SELECT user_id, SUM(sent) AS events FROM (
            SELECT requester_id AS user_id, CASE WHEN sent THEN 1 ELSE 0 END AS sent FROM touched
            UNION ALL
            SELECT addressee_id, 0 FROM touched
        ) sides
        GROUP BY user_id
        """,
        period,
    )
}
