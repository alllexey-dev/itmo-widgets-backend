package dev.alllexey.itmowidgets.backend.feature.activity.service

import dev.alllexey.itmowidgets.backend.feature.activity.model.ActivePeople
import dev.alllexey.itmowidgets.backend.feature.activity.model.ActivityCalendar
import dev.alllexey.itmowidgets.backend.feature.activity.model.ClassicCohort
import dev.alllexey.itmowidgets.backend.feature.activity.model.PeopleStats
import dev.alllexey.itmowidgets.backend.feature.activity.model.RetentionCohort
import dev.alllexey.itmowidgets.backend.feature.activity.persistence.ActiveWeekRepository
import dev.alllexey.itmowidgets.backend.feature.activity.persistence.PeopleDailyRepository
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Who is active, by people rather than clients: the counts by last activity ([UserLastSeen]), their nightly history
 * and the retention of registration cohorts (docs/contracts/clients.md#people). Counts only; the one set of accounts
 * it returns is for a caller that groups them its own way and never leaves Backend.
 */
@Service
class PeopleStatistics(
    private val lastSeen: UserLastSeen,
    private val users: UserService,
    private val weeks: ActiveWeekRepository,
    private val days: PeopleDailyRepository,
    private val clock: Clock,
) {
    /**
     * The figures as of now, with one cohort per registration week whose Monday is in `[from, to]` (Moscow days), the
     * latest [MAX_COHORTS] of them, oldest first. Reads every user's last activity once.
     */
    @Transactional(readOnly = true)
    fun people(from: LocalDate, to: LocalDate): PeopleStats {
        require(!from.isAfter(to)) { "from is after to" }
        val now = clock.instant()
        val seen = lastSeen.all()
        val cohortWeeks = cohortWeeks(from, to)
        val members = members(cohortWeeks)
        return PeopleStats(
            active = ActivePeople.of(seen.values, now),
            history = days.since(ActivityCalendar.dayOf(now).minusDays(HISTORY_DAYS)),
            retention = cohortWeeks.map { retention(it, members[it].orEmpty(), seen, now) },
            classicRetention = classicRetention(cohortWeeks, members, now),
            activeIn30Days = seen.filterValues { !it.isBefore(now.minus(ActivePeople.MONTH)) }.keys,
        )
    }

    private fun cohortWeeks(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(ActivityCalendar.firstWeekFrom(from)) { it.plusWeeks(1) }.takeWhile { !it.isAfter(to) }.toList()
            .takeLast(MAX_COHORTS)

    /** The accounts of each cohort week with their registration times. */
    private fun members(cohortWeeks: List<LocalDate>): Map<LocalDate, Map<UUID, Instant>> {
        if (cohortWeeks.isEmpty()) return emptyMap()
        val registered = users.registrations(
            ActivityCalendar.startOf(cohortWeeks.first()),
            ActivityCalendar.startOf(cohortWeeks.last().plusWeeks(1)),
        )
        return registered.entries.groupBy({ ActivityCalendar.weekOf(it.value) }) { it.toPair() }.mapValues { (_, list) -> list.toMap() }
    }

    /** `retained[n-1]` for the `n` weeks that passed since the end of the cohort week, so its youngest account lived them. */
    private fun retention(week: LocalDate, members: Map<UUID, Instant>, seen: Map<UUID, Instant>, now: Instant): RetentionCohort {
        val lived = Duration.between(ActivityCalendar.startOf(week.plusWeeks(1)), now).toDays().coerceAtLeast(0) / DAYS_IN_WEEK
        val retained = (1..minOf(lived, RETENTION_WEEKS.toLong())).map { n ->
            members.count { (userId, registeredAt) ->
                val last = seen[userId]
                last != null && !last.isBefore(registeredAt.plus(Duration.ofDays(n * DAYS_IN_WEEK)))
            }.toLong()
        }
        return RetentionCohort(week, members.size.toLong(), retained)
    }

    /** `active[n]` for every finished week from the cohort week on; null before the first collected week. */
    private fun classicRetention(
        cohortWeeks: List<LocalDate>,
        members: Map<LocalDate, Map<UUID, Instant>>,
        now: Instant,
    ): List<ClassicCohort> {
        if (cohortWeeks.isEmpty()) return emptyList()
        val currentWeek = ActivityCalendar.weekOf(now)
        val collectedFrom = weeks.firstWeek()
        val cohortOf = members.flatMap { (week, accounts) -> accounts.keys.map { it to week } }.toMap()
        val counts = weeks.countByCohort(cohortOf).associate { (it.cohort to it.week) to it.users }
        return cohortWeeks.map { cohort ->
            val finished = ChronoUnit.WEEKS.between(cohort, currentWeek).coerceAtLeast(0)
            val active = (0 until finished).map { n ->
                val week = cohort.plusWeeks(n)
                if (collectedFrom == null || week.isBefore(collectedFrom)) null else counts[cohort to week] ?: 0L
            }
            ClassicCohort(cohort, members[cohort].orEmpty().size.toLong(), active)
        }
    }

    private companion object {
        const val MAX_COHORTS = 26
        const val RETENTION_WEEKS = 12
        const val HISTORY_DAYS = 90L
        const val DAYS_IN_WEEK = 7L
    }
}
