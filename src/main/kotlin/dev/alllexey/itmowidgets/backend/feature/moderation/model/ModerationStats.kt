package dev.alllexey.itmowidgets.backend.feature.moderation.model

import java.time.LocalDate

/**
 * The moderation figures of one statistics period. Cases belong to it by their opening, decisions and reports by
 * their creation; [days] has one row per day of the period.
 */
data class ModerationStats(
    val days: List<ModerationStatsDay>,
    val cases: List<ModerationCaseStats>,
    val decisions: List<ModerationDecisionCount>,
    val reports: List<ModerationReportStats>,
)

/**
 * One Europe/Moscow day: cases opened on it, cases resolved and withdrawn on it whenever opened, [backlog] the cases
 * open at its end, and [reports] created on it that still exist.
 */
data class ModerationStatsDay(
    val day: LocalDate,
    val casesOpened: Long,
    val casesResolved: Long,
    val casesWithdrawn: Long,
    val backlog: Long,
    val reports: Long,
)

/**
 * Cases of one target type and reason opened in the period and how they ended so far. The decision times are
 * `resolved_at - opened_at` over the [resolved] ones in whole seconds, null when there is none.
 */
data class ModerationCaseStats(
    val targetType: ModerationTargetType,
    val reason: ModerationCaseReason,
    val opened: Long,
    val resolved: Long,
    val withdrawn: Long,
    val medianSecondsToDecision: Long?,
    val maxSecondsToDecision: Long?,
)

/** Decisions of one target type, action and actor made in the period. */
data class ModerationDecisionCount(
    val targetType: ModerationTargetType,
    val action: ModerationAction,
    val actor: ModerationActor,
    val count: Long,
)

/** Reports of one target type and reason created in the period, and how many of them are dismissed now. */
data class ModerationReportStats(val targetType: ModerationTargetType, val reason: ReportReason, val total: Long, val dismissed: Long)

// Rows the statistics repository returns to ModerationStatistics.

/** One Europe/Moscow day of the case series; see [ModerationStatsDay]. */
data class ModerationCaseDay(val day: LocalDate, val opened: Long, val resolved: Long, val withdrawn: Long, val backlog: Long)
