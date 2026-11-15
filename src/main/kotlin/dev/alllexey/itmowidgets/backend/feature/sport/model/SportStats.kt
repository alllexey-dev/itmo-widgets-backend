package dev.alllexey.itmowidgets.backend.feature.sport.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** How a queue entry ended so far: [CANCELLED] when the user cancelled it, otherwise its status. */
enum class SportEntryOutcome { WAITING, NOTIFIED, SATISFIED, EXPIRED, GAVE_UP_NOTIFYING, CANCELLED }

/**
 * The sport figures of one statistics period. Entries belong to the period by their creation, section subscriptions
 * by theirs and occurrences by their start; [SportSubscriptionStats.active] and [SportSubscriptionStats.stopped] are
 * the state now.
 */
data class SportStats(
    val days: List<SportStatsDay>,
    val autoSign: SportEntryStats,
    val freeSign: SportEntryStats,
    val subscriptions: SportSubscriptionStats,
    val sections: List<SportSectionStats>,
    val slots: List<SportSlotStats>,
)

/** One Europe/Moscow day: entries created on it and [signUps], entries of both kinds satisfied on it. */
data class SportStatsDay(val day: LocalDate, val autoSign: SportDayEntries, val freeSign: SportDayEntries, val signUps: Long)

/** Entries of one kind created on a day and how many of them ended [SportEntryOutcome.SATISFIED]. */
data class SportDayEntries(val created: Long, val satisfied: Long)

/**
 * Entries of one kind created in the period. [outcomes] has every [SportEntryOutcome]. [SportWaitTimes] describe the
 * satisfied entries: for auto-sign from the first notification, for free-sign from the creation.
 */
data class SportEntryStats(val created: Long, val outcomes: Map<SportEntryOutcome, Long>, val waits: SportWaitTimes)

/** [timed] satisfied entries with both times; their median and 90th percentile in whole seconds, null when [timed] is 0. */
data class SportWaitTimes(val timed: Long, val medianSeconds: Long?, val p90Seconds: Long?) {
    companion object {
        val NONE = SportWaitTimes(0, null, null)
    }
}

/** [stopped] has every [SportSectionStopReason] and [occurrences] every [SportSectionOccurrenceState]. */
data class SportSubscriptionStats(
    val active: Long,
    val stopped: Map<SportSectionStopReason, Long>,
    val created: Long,
    val occurrences: Map<SportSectionOccurrenceState, Long>,
)

/** Entries of one section; [medianWaitSeconds] is from creation to satisfaction over the satisfied entries of both kinds. */
data class SportSectionStats(
    val sectionName: String,
    val autoSignEntries: Long,
    val freeSignEntries: Long,
    val satisfied: Long,
    val medianWaitSeconds: Long?,
)

/** Auto-sign entries by the weekday and start time of the lesson they target, in Europe/Moscow. */
data class SportSlotStats(val weekday: DayOfWeek, val timeStart: LocalTime, val entries: Long, val satisfied: Long)

// Rows the statistics repository returns to SportStatistics.

/** Entries of one kind and outcome created in a period. */
data class SportOutcomeCount(val kind: SportQueueKind, val outcome: SportEntryOutcome, val count: Long)

/** Entries of one kind created on [day], and how many of them ended satisfied. */
data class SportEntryDayCount(val kind: SportQueueKind, val day: LocalDate, val created: Long, val satisfied: Long)

/** Section subscriptions of one status and stop reason: all of them now, and those created in the period. */
data class SportSubscriptionCount(
    val status: SportSectionStatus,
    val stopReason: SportSectionStopReason?,
    val total: Long,
    val created: Long,
)
