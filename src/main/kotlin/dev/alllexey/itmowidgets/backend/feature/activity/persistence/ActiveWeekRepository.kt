package dev.alllexey.itmowidgets.backend.feature.activity.persistence

import dev.alllexey.itmowidgets.backend.feature.activity.model.CohortWeekCount
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.SqlArrayValue
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

/** `stats_user_active_weeks` over JDBC: a two-column key table that JPA adds nothing to. */
@Repository
class ActiveWeekRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** Records that [userId] was active in the week of Monday [week]; true when the row is new. */
    fun record(userId: UUID, week: LocalDate): Boolean = jdbc.update(
        "INSERT INTO stats_user_active_weeks (user_id, week_start) VALUES (:userId, :week) ON CONFLICT DO NOTHING",
        mapOf("userId" to userId, "week" to week),
    ) == 1

    /** The accounts already recorded for the week of Monday [week]. */
    fun usersIn(week: LocalDate): List<UUID> =
        jdbc.queryForList("SELECT user_id FROM stats_user_active_weeks WHERE week_start = :week", mapOf("week" to week), UUID::class.java)

    /** The first week any existing account has a row for: when the collection started, as far as the rows tell. */
    fun firstWeek(): LocalDate? =
        jdbc.queryForObject("SELECT min(week_start) FROM stats_user_active_weeks", emptyMap<String, Any>(), LocalDate::class.java)

    /**
     * For accounts mapped to the Monday of their registration week, how many of each cohort have a row for each week
     * from their cohort week on. Weeks without a row of the cohort are left out.
     */
    fun countByCohort(cohorts: Map<UUID, LocalDate>): List<CohortWeekCount> {
        if (cohorts.isEmpty()) return emptyList()
        val entries = cohorts.entries.toList()
        return jdbc.query(
            """
            SELECT c.cohort, w.week_start, count(*) AS users
            FROM unnest(:ids, :cohorts) AS c (user_id, cohort)
            JOIN stats_user_active_weeks w ON w.user_id = c.user_id AND w.week_start >= c.cohort
            GROUP BY 1, 2
            """,
            mapOf(
                "ids" to SqlArrayValue("uuid", *entries.map { it.key }.toTypedArray()),
                "cohorts" to SqlArrayValue("date", *entries.map { it.value }.toTypedArray()),
            ),
        ) { rs, _ -> CohortWeekCount(rs.getObject(1, LocalDate::class.java), rs.getObject(2, LocalDate::class.java), rs.getLong(3)) }
    }
}
