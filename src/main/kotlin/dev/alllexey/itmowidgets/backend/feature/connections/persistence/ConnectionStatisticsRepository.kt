package dev.alllexey.itmowidgets.backend.feature.connections.persistence

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionDayCount
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.queryUsage
import dev.alllexey.itmowidgets.backend.platform.stats.sqlParameters
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/** Aggregates over connections for statistics. Every method is one query and returns counts. */
@Repository
class ConnectionStatisticsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** Connections created and revoked per client and Europe/Moscow day; a day without either has no row. */
    fun perDay(period: StatsPeriod): List<ConnectionDayCount> = jdbc.query(
        """
        SELECT client, day, SUM(created) AS created, SUM(revoked) AS revoked FROM (
            SELECT client, CAST(created_at AT TIME ZONE :zone AS date) AS day, 1 AS created, 0 AS revoked FROM connections
            WHERE created_at >= :start AND created_at < :end
            UNION ALL
            SELECT client, CAST(revoked_at AT TIME ZONE :zone AS date), 0, 1 FROM connections
            WHERE revoked_at >= :start AND revoked_at < :end
        ) changes
        GROUP BY client, day
        """,
        period.sqlParameters(),
    ) { rs, _ ->
        ConnectionDayCount(
            client = ClientType.valueOf(rs.getString("client")),
            day = rs.getObject("day", LocalDate::class.java),
            created = rs.getLong("created"),
            revoked = rs.getLong("revoked"),
        )
    }

    /** Connections created in the period and their users. */
    fun connectionUsage(period: StatsPeriod): FeatureUsage = jdbc.queryUsage(
        """
        SELECT user_id, COUNT(*) AS events FROM connections
        WHERE created_at >= :start AND created_at < :end GROUP BY user_id
        """,
        period,
    )
}
