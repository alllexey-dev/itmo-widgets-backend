package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportDayEntries
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryDayCount
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryOutcome
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryStats
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportOutcomeCount
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportQueueKind
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionOccurrenceState
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionStatus
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionStopReason
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportStats
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportStatsDay
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSubscriptionStats
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportWaitTimes
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The sport figures of a statistics period: eight aggregate queries in one read-only transaction, no entries loaded. */
@Service
class SportStatistics(private val repository: SportStatisticsRepository) {
    @Transactional(readOnly = true)
    fun stats(period: StatsPeriod): SportStats {
        val outcomes = repository.outcomes(period)
        val perDay = repository.entriesPerDay(period)
        val signUps = period.zeroFilled(repository.signUpsPerDay(period))
        val waits = repository.waitTimes(period)
        val autoDays = DailyEntries(period, perDay, SportQueueKind.AUTO)
        val freeDays = DailyEntries(period, perDay, SportQueueKind.FREE)
        val days = signUps.mapIndexed { index, signUp ->
            SportStatsDay(signUp.day, autoDays[index], freeDays[index], signUp.count)
        }
        return SportStats(
            days = days,
            autoSign = entryStats(SportQueueKind.AUTO, outcomes, waits),
            freeSign = entryStats(SportQueueKind.FREE, outcomes, waits),
            subscriptions = subscriptions(period),
            sections = repository.sections(period, MAX_SECTIONS),
            slots = repository.autoSignSlots(period),
        )
    }

    private fun entryStats(
        kind: SportQueueKind,
        outcomes: List<SportOutcomeCount>,
        waits: Map<SportQueueKind, SportWaitTimes>,
    ): SportEntryStats {
        val byOutcome = outcomes.filter { it.kind == kind }.associate { it.outcome to it.count }
        return SportEntryStats(
            created = byOutcome.values.sum(),
            outcomes = SportEntryOutcome.entries.associateWith { byOutcome[it] ?: 0 },
            waits = waits[kind] ?: SportWaitTimes.NONE,
        )
    }

    private fun subscriptions(period: StatsPeriod): SportSubscriptionStats {
        val counts = repository.subscriptions(period)
        val occurrences = repository.occurrences(period)
        return SportSubscriptionStats(
            active = counts.filter { it.status == SportSectionStatus.ACTIVE }.sumOf { it.total },
            stopped = SportSectionStopReason.entries.associateWith { reason ->
                counts.filter { it.status == SportSectionStatus.STOPPED && it.stopReason == reason }.sumOf { it.total }
            },
            created = counts.sumOf { it.created },
            occurrences = SportSectionOccurrenceState.entries.associateWith { occurrences[it] ?: 0 },
        )
    }

    /** The zero-filled created and satisfied series of one kind, read by the index of the period's day. */
    private class DailyEntries(period: StatsPeriod, rows: List<SportEntryDayCount>, kind: SportQueueKind) {
        private val created = period.zeroFilled(rows.filter { it.kind == kind }.map { DayCount(it.day, it.created) })
        private val satisfied = period.zeroFilled(rows.filter { it.kind == kind }.map { DayCount(it.day, it.satisfied) })

        operator fun get(index: Int) = SportDayEntries(created[index].count, satisfied[index].count)
    }

    companion object {
        /** The most popular sections an answer lists. */
        const val MAX_SECTIONS = 50
    }
}
