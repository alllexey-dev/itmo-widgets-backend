package dev.alllexey.itmowidgets.backend.feature.schedule.persistence

import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.queryUsers
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** Aggregates over uploaded lessons for statistics. Every method is one query and returns no lessons. */
@Repository
class ScheduleStatisticsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** The users with an uploaded lesson dated in the period; lessons name their user by ISU. */
    fun scheduleUsage(period: StatsPeriod): FeatureUsage = jdbc.queryUsers(
        """
        SELECT u.id AS user_id FROM users u
        WHERE EXISTS (SELECT 1 FROM lessons l WHERE l.user_isu = u.isu AND l.date BETWEEN :from AND :to)
        """,
        period,
    )
}
