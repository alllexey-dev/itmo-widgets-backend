package dev.alllexey.itmowidgets.backend.feature.push.service

import dev.alllexey.itmowidgets.backend.feature.push.model.PushOutcome
import dev.alllexey.itmowidgets.backend.feature.push.model.PushStats
import dev.alllexey.itmowidgets.backend.feature.push.model.PushTypeStats
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounterReader
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * The push figures of a statistics period from the counters [DeviceService] keeps (docs/contracts/server-statistics.md):
 * two sums of `stats_daily` in a read-only transaction. A label that is no [PushOutcome] is left out.
 */
@Service
class PushStatistics(private val reader: DailyCounterReader) {
    @Transactional(readOnly = true)
    fun stats(period: StatsPeriod): PushStats {
        val byDay = reader.byDayAndDim2(PushOutcome.METRIC, period)
        return PushStats(
            sent = byDay.series(PushOutcome.SENT),
            failed = byDay.series(PushOutcome.FAILED),
            tokenGone = byDay.series(PushOutcome.TOKEN_GONE),
            types = reader.byDim1AndDim2(PushOutcome.METRIC, period)
                .map { (type, counts) -> PushTypeStats(type, PushOutcome.entries.associateWith { counts[it.name] ?: 0 }) }
                .sortedWith(compareByDescending<PushTypeStats> { it.outcomes.values.sum() }.thenBy { it.type }),
        )
    }

    private fun Map<LocalDate, Map<String, Long>>.series(outcome: PushOutcome): List<DayCount> =
        mapNotNull { (day, counts) -> counts[outcome.name]?.let { DayCount(day, it) } }
}
