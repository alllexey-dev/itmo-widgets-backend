package dev.alllexey.itmowidgets.backend.feature.credentials.service

import dev.alllexey.itmowidgets.backend.feature.credentials.model.MyItmoOperationStats
import dev.alllexey.itmowidgets.backend.feature.credentials.model.MyItmoStats
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoFailureKind.AUTH
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoFailureKind.NETWORK
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounterKey
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounterReader
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounterStore
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.LocalDate
import kotlin.test.assertEquals

/** The My ITMO figures over synthetic `stat_daily` rows in 2032, a year no other test counts in. */
@Import(MyItmoStatistics::class, DailyCounterReader::class, DailyCounterStore::class)
class MyItmoStatisticsTest @Autowired constructor(private val statistics: MyItmoStatistics, private val counters: DailyCounterStore) :
    PostgreSqlRepositoryTest() {
    private val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-03"))

    @Test
    fun `calls count every result and failures only the failure kinds, per day and per operation`() {
        counters.add(
            mapOf(
                count("2032-02-29", MyItmoOperation.SPORT_SCHEDULE, AUTH.name) to 100L,
                count("2032-03-01", MyItmoOperation.SPORT_SCHEDULE, MyItmoOperation.OK) to 10L,
                count("2032-03-01", MyItmoOperation.SPORT_SCHEDULE, NETWORK.name) to 2L,
                count("2032-03-01", MyItmoOperation.PERSONALITY, AUTH.name) to 1L,
                count("2032-03-03", MyItmoOperation.PERSONALITY, MyItmoOperation.OK) to 3L,
                // A result this build does not know is a call, not a failure.
                count("2032-03-03", MyItmoOperation.PERSONALITY, "TIMEOUT") to 1L,
            ),
        )

        assertEquals(
            MyItmoStats(
                calls = listOf(DayCount(LocalDate.parse("2032-03-01"), 13), DayCount(LocalDate.parse("2032-03-03"), 4)),
                failures = listOf(DayCount(LocalDate.parse("2032-03-01"), 3)),
                operations = listOf(
                    MyItmoOperationStats("sportSchedule", 12, failures(NETWORK to 2)),
                    MyItmoOperationStats("personality", 5, failures(AUTH to 1)),
                ),
            ),
            statistics.stats(period),
        )
    }

    @Test
    fun `a period without counters is empty`() {
        assertEquals(
            MyItmoStats(emptyList(), emptyList(), emptyList()),
            statistics.stats(StatsPeriod(LocalDate.parse("2032-07-01"), LocalDate.parse("2032-07-02"))),
        )
    }

    private fun count(day: String, operation: MyItmoOperation, result: String) =
        DailyCounterKey(LocalDate.parse(day), MyItmoOperation.METRIC, operation.counterName, result)

    private fun failures(vararg given: Pair<MyItmoFailureKind, Int>): Map<MyItmoFailureKind, Long> {
        val map = given.toMap()
        return MyItmoFailureKind.entries.associateWith { (map[it] ?: 0).toLong() }
    }
}
