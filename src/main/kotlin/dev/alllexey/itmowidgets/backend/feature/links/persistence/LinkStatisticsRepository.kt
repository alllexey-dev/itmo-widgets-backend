package dev.alllexey.itmowidgets.backend.feature.links.persistence

import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.queryDays
import dev.alllexey.itmowidgets.backend.platform.stats.queryUsage
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** Aggregates over subject links and their votes, for statistics. Every method is one query and returns counts. */
@Repository
class LinkStatisticsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** Link votes per Europe/Moscow day of their first casting; days without a vote have no row. */
    fun votesPerDay(period: StatsPeriod): List<DayCount> = jdbc.queryDays(
        """
        SELECT CAST(created_at AT TIME ZONE :zone AS date) AS day, COUNT(*) AS count FROM subject_link_votes
        WHERE created_at >= :start AND created_at < :end
        GROUP BY 1
        """,
        period,
    )

    /** Links created in the period and their owners. */
    fun linkUsage(period: StatsPeriod): FeatureUsage = jdbc.queryUsage(
        """
        SELECT owner_id AS user_id, COUNT(*) AS events FROM subject_links
        WHERE created_at >= :start AND created_at < :end GROUP BY owner_id
        """,
        period,
    )

    /** Link votes cast in the period and their voters. */
    fun voteUsage(period: StatsPeriod): FeatureUsage = jdbc.queryUsage(
        """
        SELECT user_id, COUNT(*) AS events FROM subject_link_votes
        WHERE created_at >= :start AND created_at < :end GROUP BY user_id
        """,
        period,
    )
}
