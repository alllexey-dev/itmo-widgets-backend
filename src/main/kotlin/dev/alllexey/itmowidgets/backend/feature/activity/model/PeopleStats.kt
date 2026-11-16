package dev.alllexey.itmowidgets.backend.feature.activity.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Distinct people whose last activity is within the last day, 7 days and 30 days. */
data class ActivePeople(val active1d: Long, val active7d: Long, val active30d: Long) {
    companion object {
        val DAY: Duration = Duration.ofDays(1)
        val WEEK: Duration = Duration.ofDays(7)
        val MONTH: Duration = Duration.ofDays(30)

        /** The counts as of [now] over the last activity of every person. */
        fun of(lastSeen: Collection<Instant>, now: Instant): ActivePeople = ActivePeople(
            lastSeen.count { !it.isBefore(now.minus(DAY)) }.toLong(),
            lastSeen.count { !it.isBefore(now.minus(WEEK)) }.toLong(),
            lastSeen.count { !it.isBefore(now.minus(MONTH)) }.toLong(),
        )
    }
}

/** A `stats_people_daily` row: [active] as the nightly job counted it right after [day] ended. */
data class PeopleDay(val day: LocalDate, val active: ActivePeople)

/**
 * The unbounded retention of the accounts registered in the week of [weekStart]: `retained[n-1]` is how many of the
 * [size] accounts have a last activity at least `n` weeks after their own registration. Only the `n` from 1 to 12 the
 * whole cohort has lived through are present.
 */
data class RetentionCohort(val weekStart: LocalDate, val size: Long, val retained: List<Long>)

/**
 * The classic retention of the accounts registered in the week of [weekStart]: `active[n]` is how many of the [size]
 * accounts made a request in the `n`-th calendar week after it (`active[0]` is the registration week), one element per
 * finished week; null for a week before the collection of activity weeks started.
 */
data class ClassicCohort(val weekStart: LocalDate, val size: Long, val active: List<Long?>)

/** How many accounts of the cohort week [cohort] have an activity row for [week]. */
data class CohortWeekCount(val cohort: LocalDate, val week: LocalDate, val users: Long)

/**
 * Everything `PeopleStatistics` knows for one request, computed over one read of every user's last activity.
 * [activeIn30Days] are the accounts counted in [ActivePeople.active30d], for a caller that groups them its own way.
 */
data class PeopleStats(
    val active: ActivePeople,
    val history: List<PeopleDay>,
    val retention: List<RetentionCohort>,
    val classicRetention: List<ClassicCohort>,
    val activeIn30Days: Set<UUID>,
)
