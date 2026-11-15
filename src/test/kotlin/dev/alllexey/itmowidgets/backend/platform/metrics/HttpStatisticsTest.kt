package dev.alllexey.itmowidgets.backend.platform.metrics

import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.LocalDate
import kotlin.test.assertEquals

/** The HTTP figures over synthetic `stat_daily` rows in 2032, a year no other test counts in. */
@Import(HttpStatistics::class, DailyCounterReader::class, DailyCounterStore::class)
class HttpStatisticsTest @Autowired constructor(private val statistics: HttpStatistics, private val counters: DailyCounterStore) :
    PostgreSqlRepositoryTest() {
    private val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-02"))

    @Test
    fun `answers per day and per route group, with errors by status class and times from the buckets`() {
        counters.add(
            mapOf(
                http("2032-02-29", "sport", "5xx") to 100L,
                http("2032-03-01", "sport", "2xx") to 8L,
                http("2032-03-01", "sport", "5xx") to 2L,
                http("2032-03-01", "friends", "4xx") to 3L,
                http("2032-03-02", "friends", "2xx") to 1L,
                http("2032-03-02", "friends", "3xx") to 1L,
                // The long-poll counts as an answer, but is not timed.
                http("2032-03-02", "client", "2xx") to 20L,
                ms("2032-03-01", "sport", LatencyBucket.MS_10) to 5L,
                ms("2032-03-01", "sport", LatencyBucket.MS_100) to 5L,
                ms("2032-03-01", "friends", LatencyBucket.INF) to 3L,
                ms("2032-03-02", "friends", LatencyBucket.MS_25) to 2L,
            ),
        )

        val stats = statistics.stats(period)

        assertEquals(listOf(DayCount(day("2032-03-01"), 13), DayCount(day("2032-03-02"), 22)), stats.requests)
        assertEquals(listOf(DayCount(day("2032-03-01"), 2)), stats.errors5xx)
        // 2032-03-01: 13 timed answers; the median's rank 6.5 is the 1.5th of the five in 50-100 ms, p95's rank 12.35 is
        // in inf. 2032-03-02: two answers in 10-25 ms.
        assertEquals(listOf(DayLatency(day("2032-03-01"), 65, 5000), DayLatency(day("2032-03-02"), 18, 24)), stats.latency)
        assertEquals(
            listOf(
                RouteStats("client", 20, 0, 0, null, null),
                RouteStats("sport", 10, 0, 2, 10, 95),
                // Three of its five timed answers took over 5 s.
                RouteStats("friends", 5, 3, 0, 5000, 5000),
            ),
            stats.routes,
        )
    }

    @Test
    fun `a period without counters is empty`() {
        assertEquals(
            HttpStats(emptyList(), emptyList(), emptyList(), emptyList()),
            statistics.stats(StatsPeriod(LocalDate.parse("2032-07-01"), LocalDate.parse("2032-07-02"))),
        )
    }

    private fun http(day: String, group: String, statusClass: String) = DailyCounterKey(day(day), HttpCounters.HTTP, group, statusClass)

    private fun ms(day: String, group: String, bucket: LatencyBucket) = DailyCounterKey(day(day), HttpCounters.HTTP_MS, group, bucket.label)

    private fun day(text: String): LocalDate = LocalDate.parse(text)
}
