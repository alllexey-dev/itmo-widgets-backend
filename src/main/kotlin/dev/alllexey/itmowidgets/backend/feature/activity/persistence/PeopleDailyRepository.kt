package dev.alllexey.itmowidgets.backend.feature.activity.persistence

import dev.alllexey.itmowidgets.backend.feature.activity.model.ActivePeople
import dev.alllexey.itmowidgets.backend.feature.activity.model.PeopleDay
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate

/** `stats_people_daily` over JDBC: one row of three counts per day. */
@Repository
class PeopleDailyRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** Writes the row of [day] unless it exists: the first count of a day stays. True when the row is new. */
    fun insert(day: LocalDate, active: ActivePeople): Boolean = jdbc.update(
        """
        INSERT INTO stats_people_daily (day, active_1d, active_7d, active_30d) VALUES (:day, :active1d, :active7d, :active30d)
        ON CONFLICT (day) DO NOTHING
        """,
        mapOf("day" to day, "active1d" to active.active1d, "active7d" to active.active7d, "active30d" to active.active30d),
    ) == 1

    /** The rows from [from] on, oldest first. */
    fun since(from: LocalDate): List<PeopleDay> = jdbc.query(
        "SELECT day, active_1d, active_7d, active_30d FROM stats_people_daily WHERE day >= :from ORDER BY day",
        mapOf("from" to from),
    ) { rs, _ -> PeopleDay(rs.getObject(1, LocalDate::class.java), ActivePeople(rs.getLong(2), rs.getLong(3), rs.getLong(4))) }
}
