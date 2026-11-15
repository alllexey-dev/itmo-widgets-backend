package dev.alllexey.itmowidgets.backend.feature.users.persistence

import dev.alllexey.itmowidgets.backend.feature.users.model.PrivacyCounts
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Aggregates over accounts for statistics. Every method is one query and returns counts. Placeholders of deleted
 * accounts have a negative ISU and are not counted.
 */
@Repository
class UserStatisticsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun accounts(): Long = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE isu > 0", emptyMap<String, Any>(), Long::class.java)!!

    /**
     * Accounts per value of each privacy setting, in one pass: each grouping set yields rows whose other two columns
     * are null (the columns themselves are never null).
     */
    fun privacy(): PrivacyCounts {
        val schedule = mutableMapOf<SharingVisibility, Long>()
        val sport = mutableMapOf<SharingVisibility, Long>()
        val friends = mutableMapOf<SharingVisibility, Long>()
        jdbc.query(
            """
            SELECT s.schedule_visibility AS schedule, s.sport_visibility AS sport, s.friends_visibility AS friends, COUNT(*) AS count
            FROM users u JOIN user_settings s ON s.user_id = u.id
            WHERE u.isu > 0
            GROUP BY GROUPING SETS ((s.schedule_visibility), (s.sport_visibility), (s.friends_visibility))
            """,
            RowCallbackHandler { rs ->
                val count = rs.getLong("count")
                rs.getString("schedule")?.let { schedule[SharingVisibility.valueOf(it)] = count }
                rs.getString("sport")?.let { sport[SharingVisibility.valueOf(it)] = count }
                rs.getString("friends")?.let { friends[SharingVisibility.valueOf(it)] = count }
            },
        )
        return PrivacyCounts(schedule.complete(), sport.complete(), friends.complete())
    }

    private fun Map<SharingVisibility, Long>.complete(): Map<SharingVisibility, Long> =
        SharingVisibility.entries.associateWith { this[it] ?: 0 }
}
