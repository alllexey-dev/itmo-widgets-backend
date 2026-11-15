package dev.alllexey.itmowidgets.backend.feature.moderation.persistence

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationActor
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseDay
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStats
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionCount
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationReportStats
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ReportReason
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
import java.time.LocalDate

/**
 * Aggregates over moderation cases, decisions and reports for statistics, in plain SQL: `percentile_cont`, `FILTER`,
 * window sums and time zones are not JPQL. Every method is one query and returns counts, never rows of users.
 */
@Repository
class ModerationStatisticsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /**
     * Every day of the period, oldest first: cases opened on it, resolved and withdrawn on it, and the backlog at its
     * end. The backlog is the cases open before the period plus a running sum of the days' changes, so the whole
     * series is one query.
     */
    fun casesPerDay(period: StatsPeriod): List<ModerationCaseDay> = jdbc.query(
        """
        WITH days AS (
            SELECT CAST(d AS date) AS day
            FROM generate_series(CAST(:from AS timestamp), CAST(:to AS timestamp), interval '1 day') d
        ),
        opened AS (
            SELECT CAST(opened_at AT TIME ZONE :zone AS date) AS day, COUNT(*) AS opened FROM moderation_cases
            WHERE opened_at >= :start AND opened_at < :end
            GROUP BY 1
        ),
        closed AS (
            SELECT CAST(resolved_at AT TIME ZONE :zone AS date) AS day,
                COUNT(*) FILTER (WHERE status = 'RESOLVED') AS resolved,
                COUNT(*) FILTER (WHERE status = 'WITHDRAWN') AS withdrawn
            FROM moderation_cases
            WHERE resolved_at >= :start AND resolved_at < :end
            GROUP BY 1
        ),
        open_before AS (
            SELECT COUNT(*) AS backlog FROM moderation_cases
            WHERE opened_at < :start AND (resolved_at IS NULL OR resolved_at >= :start)
        )
        SELECT d.day, COALESCE(o.opened, 0) AS opened, COALESCE(c.resolved, 0) AS resolved,
            COALESCE(c.withdrawn, 0) AS withdrawn,
            (SELECT backlog FROM open_before)
                + SUM(COALESCE(o.opened, 0) - COALESCE(c.resolved, 0) - COALESCE(c.withdrawn, 0)) OVER (ORDER BY d.day) AS backlog
        FROM days d
        LEFT JOIN opened o ON o.day = d.day
        LEFT JOIN closed c ON c.day = d.day
        ORDER BY d.day
        """,
        period.sqlParameters(),
    ) { rs, _ ->
        ModerationCaseDay(
            day = rs.getObject("day", LocalDate::class.java),
            opened = rs.getLong("opened"),
            resolved = rs.getLong("resolved"),
            withdrawn = rs.getLong("withdrawn"),
            backlog = rs.getLong("backlog"),
        )
    }

    /** Cases opened in the period by target type and reason, how they ended and how long the resolved ones took. */
    fun cases(period: StatsPeriod): List<ModerationCaseStats> = jdbc.query(
        """
        SELECT target_type, reason, COUNT(*) AS opened,
            COUNT(*) FILTER (WHERE status = 'RESOLVED') AS resolved,
            COUNT(*) FILTER (WHERE status = 'WITHDRAWN') AS withdrawn,
            percentile_cont(0.5) WITHIN GROUP (ORDER BY CAST(EXTRACT(EPOCH FROM resolved_at - opened_at) AS double precision))
                FILTER (WHERE status = 'RESOLVED') AS median,
            MAX(CAST(EXTRACT(EPOCH FROM resolved_at - opened_at) AS double precision)) FILTER (WHERE status = 'RESOLVED') AS longest
        FROM moderation_cases
        WHERE opened_at >= :start AND opened_at < :end
        GROUP BY target_type, reason
        ORDER BY target_type, reason
        """,
        period.sqlParameters(),
    ) { rs, _ ->
        ModerationCaseStats(
            targetType = rs.targetType(),
            reason = ModerationCaseReason.valueOf(rs.getString("reason")),
            opened = rs.getLong("opened"),
            resolved = rs.getLong("resolved"),
            withdrawn = rs.getLong("withdrawn"),
            medianSecondsToDecision = rs.seconds("median"),
            maxSecondsToDecision = rs.seconds("longest"),
        )
    }

    /** Decisions made in the period and not reverted, by the target type of their case, action and actor. */
    fun decisions(period: StatsPeriod): List<ModerationDecisionCount> = jdbc.query(
        """
        SELECT c.target_type, d.action, d.actor, COUNT(*) AS count
        FROM moderation_decisions d JOIN moderation_cases c ON c.id = d.case_id
        WHERE d.created_at >= :start AND d.created_at < :end AND $DECISION_NOT_REVERTED
        GROUP BY 1, 2, 3
        ORDER BY 1, 2, 3
        """,
        period.sqlParameters(),
    ) { rs, _ ->
        ModerationDecisionCount(
            targetType = rs.targetType(),
            action = ModerationAction.valueOf(rs.getString("action")),
            actor = ModerationActor.valueOf(rs.getString("actor")),
            count = rs.getLong("count"),
        )
    }

    /** Reports created in the period by target type and reason, with those dismissed now. */
    fun reports(period: StatsPeriod): List<ModerationReportStats> = jdbc.query(
        """
        SELECT target_type, reason, COUNT(*) AS total, COUNT(*) FILTER (WHERE dismissed_at IS NOT NULL) AS dismissed
        FROM moderation_reports
        WHERE created_at >= :start AND created_at < :end
        GROUP BY target_type, reason
        ORDER BY target_type, reason
        """,
        period.sqlParameters(),
    ) { rs, _ ->
        ModerationReportStats(rs.targetType(), ReportReason.valueOf(rs.getString("reason")), rs.getLong("total"), rs.getLong("dismissed"))
    }

    /** Reports created per Europe/Moscow day; days without one have no row. */
    fun reportsPerDay(period: StatsPeriod): List<DayCount> = jdbc.queryDays(
        """
        SELECT CAST(created_at AT TIME ZONE :zone AS date) AS day, COUNT(*) AS count FROM moderation_reports
        WHERE created_at >= :start AND created_at < :end
        GROUP BY 1
        """,
        period,
    )

    /** Reports created in the period and their reporters. */
    fun reportUsage(period: StatsPeriod): FeatureUsage = jdbc.queryUsage(
        """
        SELECT reporter_id AS user_id, COUNT(*) AS events FROM moderation_reports
        WHERE created_at >= :start AND created_at < :end GROUP BY reporter_id
        """,
        period,
    )

    private fun ResultSet.targetType(): ModerationTargetType = ModerationTargetType.valueOf(getString("target_type"))
}
