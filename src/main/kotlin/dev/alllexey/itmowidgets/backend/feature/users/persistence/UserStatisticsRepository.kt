package dev.alllexey.itmowidgets.backend.feature.users.persistence

import dev.alllexey.itmowidgets.backend.feature.users.model.AccountGroups
import dev.alllexey.itmowidgets.backend.feature.users.model.CourseAccounts
import dev.alllexey.itmowidgets.backend.feature.users.model.FacultyAccounts
import dev.alllexey.itmowidgets.backend.feature.users.model.PrivacyCounts
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.queryDays
import dev.alllexey.itmowidgets.backend.platform.stats.sqlParameters
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.SqlArrayValue
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Aggregates over accounts for statistics. Every method is one query. [ACCOUNT] is the one definition of an account
 * for every figure: the placeholder that keeps the published content of a deleted account (a negative ISU,
 * AccountDeletionService) is not one.
 */
@Repository
class UserStatisticsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun accounts(): Long = jdbc.queryForObject("SELECT COUNT(*) FROM users u WHERE $ACCOUNT", emptyMap<String, Any>(), Long::class.java)!!

    /** The accounts created at [since] or later. */
    fun createdSince(since: Instant): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM users u WHERE $ACCOUNT AND u.created_at >= :since",
        mapOf("since" to since.atOffset(ZoneOffset.UTC)),
        Long::class.java,
    )!!

    /** The accounts created before the period. */
    fun createdBefore(period: StatsPeriod): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM users u WHERE $ACCOUNT AND u.created_at < :start",
        period.sqlParameters(),
        Long::class.java,
    )!!

    /** The accounts created per Europe/Moscow day of the period; a day without one has no row. */
    fun createdPerDay(period: StatsPeriod): List<DayCount> = jdbc.queryDays(
        """
        SELECT CAST(u.created_at AT TIME ZONE :zone AS date) AS day, COUNT(*) AS count FROM users u
        WHERE $ACCOUNT AND u.created_at >= :start AND u.created_at < :end
        GROUP BY 1
        """,
        period,
    )

    /** The accounts created in `[from, until)` with their registration times. */
    fun registrations(from: Instant, until: Instant): Map<UUID, Instant> = jdbc.query(
        "SELECT u.id, u.created_at FROM users u WHERE $ACCOUNT AND u.created_at >= :from AND u.created_at < :until",
        mapOf("from" to from.atOffset(ZoneOffset.UTC), "until" to until.atOffset(ZoneOffset.UTC)),
    ) { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, OffsetDateTime::class.java).toInstant() }.toMap()

    /** The ids of the accounts among [isus]; an ISU without an account is left out. */
    fun idsByIsu(isus: Collection<Int>): Set<UUID> {
        if (isus.isEmpty()) return emptySet()
        return jdbc.queryForList(
            "SELECT u.id FROM users u WHERE $ACCOUNT AND u.isu = ANY(:isus)",
            mapOf("isus" to SqlArrayValue("integer", *isus.toTypedArray())),
            UUID::class.java,
        ).toSet()
    }

    /** The accounts among [ids]; a placeholder or an unknown id is left out. */
    fun accountsAmong(ids: Set<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return jdbc.queryForList(
            "SELECT u.id FROM users u WHERE $ACCOUNT AND u.id = ANY(:ids)",
            mapOf("ids" to SqlArrayValue("uuid", *ids.toTypedArray())),
            UUID::class.java,
        ).toSet()
    }

    /**
     * Every account by the faculty and by the course of its current group in one pass, with how many of each are in
     * [selected]. `GROUPING(c.course)` tells a faculty row (1) from a course row (0), since both may hold nulls.
     */
    fun groups(selected: Set<UUID>): AccountGroups {
        val faculties = mutableListOf<FacultyAccounts>()
        val courses = mutableListOf<CourseAccounts>()
        jdbc.query(
            """
            WITH current_group AS (
                SELECT DISTINCT ON (ug.user_id) ug.user_id, g.course, g.faculty_id
                FROM user_study_groups ug JOIN study_groups g ON g.id = ug.group_id
                ORDER BY ug.user_id, g.course DESC, g.name
            )
            SELECT f.short_name, f.name, c.course, GROUPING(c.course) AS faculty_row, COUNT(*) AS accounts,
                COUNT(*) FILTER (WHERE u.id = ANY(:selected)) AS selected
            FROM users u
            LEFT JOIN current_group c ON c.user_id = u.id
            LEFT JOIN faculties f ON f.id = c.faculty_id
            WHERE $ACCOUNT
            GROUP BY GROUPING SETS ((f.id, f.short_name, f.name), (c.course))
            """,
            mapOf("selected" to SqlArrayValue("uuid", *selected.toTypedArray())),
            RowCallbackHandler { rs ->
                val accounts = rs.getLong("accounts")
                val inSelected = rs.getLong("selected")
                if (rs.getInt("faculty_row") == 1) {
                    faculties += FacultyAccounts(rs.getString("short_name"), rs.getString("name"), accounts, inSelected)
                } else {
                    courses += CourseAccounts(rs.getInt("course").takeUnless { rs.wasNull() }, accounts, inSelected)
                }
            },
        )
        return AccountGroups(
            faculties = faculties.sortedWith(
                compareBy<FacultyAccounts> { it.shortName == null }.thenByDescending { it.accounts }.thenBy { it.shortName },
            ),
            courses = courses.sortedWith(compareBy<CourseAccounts> { it.course == null }.thenBy { it.course }),
        )
    }

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
            WHERE $ACCOUNT
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

    private companion object {
        /** The condition on `users u` of a row that is an account. */
        const val ACCOUNT = "u.isu > 0"
    }
}
