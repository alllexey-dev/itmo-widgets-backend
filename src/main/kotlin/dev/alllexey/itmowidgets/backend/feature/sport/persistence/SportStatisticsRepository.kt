package dev.alllexey.itmowidgets.backend.feature.sport.persistence

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryDayCount
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryOutcome
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportOutcomeCount
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportQueueKind
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionOccurrenceState
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionStats
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionStatus
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionStopReason
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSlotStats
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSubscriptionCount
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportWaitTimes
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.queryDays
import dev.alllexey.itmowidgets.backend.platform.stats.queryUsage
import dev.alllexey.itmowidgets.backend.platform.stats.seconds
import dev.alllexey.itmowidgets.backend.platform.stats.sqlParameters
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Aggregates over the sport entry, lesson and section tables for statistics, in plain SQL: `percentile_cont`, `FILTER`
 * and time zones are not JPQL. Every method is one query and returns counts, never entries. Entries of both kinds are
 * read through [ENTRIES], the one place that says which entries belong to a period and what their outcome is.
 */
@Repository
class SportStatisticsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun outcomes(period: StatsPeriod): List<SportOutcomeCount> = jdbc.query(
        "$ENTRIES SELECT kind, outcome, COUNT(*) AS count FROM entries GROUP BY kind, outcome",
        period.sqlParameters(),
    ) { rs, _ -> SportOutcomeCount(rs.kind(), SportEntryOutcome.valueOf(rs.getString("outcome")), rs.getLong("count")) }

    /** Entries created per Europe/Moscow day; days without entries have no row. */
    fun entriesPerDay(period: StatsPeriod): List<SportEntryDayCount> = jdbc.query(
        """
        $ENTRIES
        SELECT kind, CAST(created_at AT TIME ZONE :zone AS date) AS day, COUNT(*) AS created,
            COUNT(*) FILTER (WHERE outcome = 'SATISFIED') AS satisfied
        FROM entries GROUP BY 1, 2
        """,
        period.sqlParameters(),
    ) { rs, _ -> SportEntryDayCount(rs.kind(), rs.getObject("day", LocalDate::class.java), rs.getLong("created"), rs.getLong("satisfied")) }

    /** Wait times of satisfied entries per kind: auto-sign from the first notification, free-sign from the creation. */
    fun waitTimes(period: StatsPeriod): Map<SportQueueKind, SportWaitTimes> = jdbc.query(
        """
        $ENTRIES
        SELECT kind, COUNT(*) AS timed,
            percentile_cont(0.5) WITHIN GROUP (ORDER BY seconds) AS median,
            percentile_cont(0.9) WITHIN GROUP (ORDER BY seconds) AS p90
        FROM (
            SELECT kind,
                CAST(EXTRACT(EPOCH FROM satisfied_at - CASE WHEN kind = 'AUTO' THEN first_notified_at ELSE created_at END)
                    AS double precision) AS seconds
            FROM entries WHERE outcome = 'SATISFIED'
        ) waits
        WHERE seconds IS NOT NULL
        GROUP BY kind
        """,
        period.sqlParameters(),
    ) { rs, _ -> rs.kind() to SportWaitTimes(rs.getLong("timed"), rs.seconds("median"), rs.seconds("p90")) }.toMap()

    /**
     * Sections by entries of both kinds, most first, at most [limit]: an auto entry under the section it targets, a
     * free entry under its lesson's. Ties go by name.
     */
    fun sections(period: StatsPeriod, limit: Int): List<SportSectionStats> = jdbc.query(
        """
        $ENTRIES
        SELECT section_name,
            COUNT(*) FILTER (WHERE kind = 'AUTO') AS auto_sign,
            COUNT(*) FILTER (WHERE kind = 'FREE') AS free_sign,
            COUNT(*) FILTER (WHERE outcome = 'SATISFIED') AS satisfied,
            percentile_cont(0.5) WITHIN GROUP (ORDER BY CAST(EXTRACT(EPOCH FROM satisfied_at - created_at) AS double precision))
                FILTER (WHERE outcome = 'SATISFIED') AS median_wait
        FROM entries
        GROUP BY section_name
        ORDER BY COUNT(*) DESC, section_name
        LIMIT :limit
        """,
        period.sqlParameters() + ("limit" to limit),
    ) { rs, _ ->
        SportSectionStats(
            sectionName = rs.getString("section_name"),
            autoSignEntries = rs.getLong("auto_sign"),
            freeSignEntries = rs.getLong("free_sign"),
            satisfied = rs.getLong("satisfied"),
            medianWaitSeconds = rs.seconds("median_wait"),
        )
    }

    /** Auto entries by the Europe/Moscow weekday and start minute of the lesson they target; only slots with an entry. */
    fun autoSignSlots(period: StatsPeriod): List<SportSlotStats> = jdbc.query(
        """
        $ENTRIES
        SELECT CAST(EXTRACT(ISODOW FROM target_starts_at AT TIME ZONE :zone) AS int) AS weekday,
            CAST(date_trunc('minute', target_starts_at AT TIME ZONE :zone) AS time) AS time_start,
            COUNT(*) AS entries, COUNT(*) FILTER (WHERE outcome = 'SATISFIED') AS satisfied
        FROM entries WHERE kind = 'AUTO'
        GROUP BY 1, 2 ORDER BY 1, 2
        """,
        period.sqlParameters(),
    ) { rs, _ ->
        SportSlotStats(
            weekday = DayOfWeek.of(rs.getInt("weekday")),
            timeStart = rs.getObject("time_start", LocalTime::class.java),
            entries = rs.getLong("entries"),
            satisfied = rs.getLong("satisfied"),
        )
    }

    /** Entries of both kinds satisfied per Europe/Moscow day of the period, whenever they were created. */
    fun signUpsPerDay(period: StatsPeriod): List<DayCount> = jdbc.queryDays(
        """
        SELECT CAST(satisfied_at AT TIME ZONE :zone AS date) AS day, COUNT(*) AS count
        FROM (
            SELECT satisfied_at FROM sport_auto_sign_entries WHERE satisfied_at >= :start AND satisfied_at < :end
            UNION ALL
            SELECT satisfied_at FROM sport_free_sign_entries WHERE satisfied_at >= :start AND satisfied_at < :end
        ) satisfied
        GROUP BY 1
        """,
        period,
    )

    /** Every section subscription by status and stop reason, with those created in the period. */
    fun subscriptions(period: StatsPeriod): List<SportSubscriptionCount> = jdbc.query(
        """
        SELECT status, stop_reason, COUNT(*) AS total,
            COUNT(*) FILTER (WHERE created_at >= :start AND created_at < :end) AS created
        FROM sport_section_subscriptions
        GROUP BY status, stop_reason
        """,
        period.sqlParameters(),
    ) { rs, _ ->
        SportSubscriptionCount(
            status = SportSectionStatus.valueOf(rs.getString("status")),
            stopReason = rs.getString("stop_reason")?.let(SportSectionStopReason::valueOf),
            total = rs.getLong("total"),
            created = rs.getLong("created"),
        )
    }

    /** Section occurrences starting in the period, by state. */
    fun occurrences(period: StatsPeriod): Map<SportSectionOccurrenceState, Long> = jdbc.query(
        """
        SELECT state, COUNT(*) AS count FROM sport_section_occurrences
        WHERE starts_at >= :start AND starts_at < :end
        GROUP BY state
        """,
        period.sqlParameters(),
    ) { rs, _ -> SportSectionOccurrenceState.valueOf(rs.getString("state")) to rs.getLong("count") }.toMap()

    /** Entries of [kind] created in the period and their distinct users. */
    fun entryUsage(kind: SportQueueKind, period: StatsPeriod): FeatureUsage = jdbc.queryUsage(
        when (kind) {
            SportQueueKind.AUTO -> AUTO_SIGN_USAGE
            SportQueueKind.FREE -> FREE_SIGN_USAGE
        },
        period,
    )

    /** Section subscriptions created in the period and their distinct users. */
    fun subscriptionUsage(period: StatsPeriod): FeatureUsage = jdbc.queryUsage(SUBSCRIPTION_USAGE, period)

    private fun ResultSet.kind(): SportQueueKind = SportQueueKind.valueOf(getString("kind"))

    private companion object {
        /**
         * Entries of both kinds created in `[:start, :end)`, with their [SportQueueKind] name as `kind` and their
         * [SportEntryOutcome] name as `outcome`. A free entry has no target start; its section is its lesson's.
         */
        const val ENTRIES = """
            WITH entries AS (
                SELECT 'AUTO' AS kind, e.user_id, e.created_at, e.first_notified_at, e.satisfied_at,
                    e.target_section_name AS section_name, e.target_starts_at,
                    CASE WHEN e.is_cancelled THEN 'CANCELLED' ELSE e.status END AS outcome
                FROM sport_auto_sign_entries e
                WHERE e.created_at >= :start AND e.created_at < :end
                UNION ALL
                SELECT 'FREE', e.user_id, e.created_at, e.first_notified_at, e.satisfied_at,
                    trim(l.section_name), CAST(NULL AS timestamptz),
                    CASE WHEN e.is_cancelled THEN 'CANCELLED' ELSE e.status END
                FROM sport_free_sign_entries e JOIN sport_lessons l ON l.id = e.lesson_id
                WHERE e.created_at >= :start AND e.created_at < :end
            )
        """

        const val AUTO_SIGN_USAGE = """
            SELECT user_id, COUNT(*) AS events FROM sport_auto_sign_entries
            WHERE created_at >= :start AND created_at < :end GROUP BY user_id
        """

        const val FREE_SIGN_USAGE = """
            SELECT user_id, COUNT(*) AS events FROM sport_free_sign_entries
            WHERE created_at >= :start AND created_at < :end GROUP BY user_id
        """

        const val SUBSCRIPTION_USAGE = """
            SELECT user_id, COUNT(*) AS events FROM sport_section_subscriptions
            WHERE created_at >= :start AND created_at < :end GROUP BY user_id
        """
    }
}
