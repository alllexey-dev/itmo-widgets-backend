package dev.alllexey.itmowidgets.backend.feature.credentials.service

import dev.alllexey.itmowidgets.backend.feature.credentials.model.MyItmoOperationStats
import dev.alllexey.itmowidgets.backend.feature.credentials.model.MyItmoStats
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounterReader
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The My ITMO figures of a statistics period from the counters [MyItmoApiGateway] keeps
 * (docs/contracts/server-statistics.md): two sums of `stat_daily` in a read-only transaction. A call counts in the
 * calls whatever its result; a failure is a result that names a [MyItmoFailureKind].
 */
@Service
class MyItmoStatistics(private val reader: DailyCounterReader) {
    @Transactional(readOnly = true)
    fun stats(period: StatsPeriod): MyItmoStats {
        val byDay = reader.byDayAndDim2(MyItmoOperation.METRIC, period)
        return MyItmoStats(
            calls = byDay.map { (day, results) -> DayCount(day, results.values.sum()) },
            failures = byDay.mapNotNull { (day, results) -> results.failures().values.sum().takeIf { it > 0 }?.let { DayCount(day, it) } },
            operations = reader.byDim1AndDim2(MyItmoOperation.METRIC, period)
                .map { (operation, results) -> MyItmoOperationStats(operation, results.values.sum(), results.failures()) }
                .sortedWith(compareByDescending<MyItmoOperationStats> { it.calls }.thenBy { it.operation }),
        )
    }

    private fun Map<String, Long>.failures(): Map<MyItmoFailureKind, Long> = MyItmoFailureKind.entries.associateWith { this[it.name] ?: 0 }
}
