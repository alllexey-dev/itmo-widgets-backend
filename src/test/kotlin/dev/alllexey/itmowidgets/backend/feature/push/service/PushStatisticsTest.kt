package dev.alllexey.itmowidgets.backend.feature.push.service

import dev.alllexey.itmowidgets.backend.feature.push.model.PushOutcome
import dev.alllexey.itmowidgets.backend.feature.push.model.PushOutcome.FAILED
import dev.alllexey.itmowidgets.backend.feature.push.model.PushOutcome.NO_DEVICE
import dev.alllexey.itmowidgets.backend.feature.push.model.PushOutcome.SENT
import dev.alllexey.itmowidgets.backend.feature.push.model.PushOutcome.TOKEN_GONE
import dev.alllexey.itmowidgets.backend.feature.push.model.PushStats
import dev.alllexey.itmowidgets.backend.feature.push.model.PushTypeStats
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

/** The push figures over synthetic `stats_daily` rows in 2032, a year no other test counts in. */
@Import(PushStatistics::class, DailyCounterReader::class, DailyCounterStore::class)
class PushStatisticsTest @Autowired constructor(private val statistics: PushStatistics, private val counters: DailyCounterStore) :
    PostgreSqlRepositoryTest() {
    private val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-02"))

    @Test
    fun `outcomes per day and per type over the period, leaving out days without a count and unknown outcomes`() {
        counters.add(
            mapOf(
                count("2032-02-29", "SPORT_LESSONS", SENT) to 100L,
                count("2032-03-01", "SPORT_LESSONS", SENT) to 5L,
                count("2032-03-01", "SPORT_LESSONS", TOKEN_GONE) to 1L,
                count("2032-03-01", "FRIEND_REQUEST", SENT) to 2L,
                count("2032-03-01", "FRIEND_REQUEST", NO_DEVICE) to 3L,
                count("2032-03-02", "FRIEND_REQUEST", FAILED) to 4L,
                DailyCounterKey(LocalDate.parse("2032-03-02"), PushOutcome.METRIC, "FRIEND_REQUEST", "DELIVERED") to 50L,
            ),
        )

        assertEquals(
            PushStats(
                sent = listOf(DayCount(LocalDate.parse("2032-03-01"), 7)),
                failed = listOf(DayCount(LocalDate.parse("2032-03-02"), 4)),
                tokenGone = listOf(DayCount(LocalDate.parse("2032-03-01"), 1)),
                types = listOf(
                    PushTypeStats("FRIEND_REQUEST", outcomes(SENT to 2, FAILED to 4, NO_DEVICE to 3)),
                    PushTypeStats("SPORT_LESSONS", outcomes(SENT to 5, TOKEN_GONE to 1)),
                ),
            ),
            statistics.stats(period),
        )
    }

    @Test
    fun `a period without counters is empty`() {
        assertEquals(
            PushStats(emptyList(), emptyList(), emptyList(), emptyList()),
            statistics.stats(StatsPeriod(LocalDate.parse("2032-07-01"), LocalDate.parse("2032-07-02"))),
        )
    }

    private fun count(day: String, type: String, outcome: PushOutcome) =
        DailyCounterKey(LocalDate.parse(day), PushOutcome.METRIC, type, outcome.name)

    private fun outcomes(vararg given: Pair<PushOutcome, Int>): Map<PushOutcome, Long> {
        val map = given.toMap()
        return PushOutcome.entries.associateWith { (map[it] ?: 0).toLong() }
    }
}
