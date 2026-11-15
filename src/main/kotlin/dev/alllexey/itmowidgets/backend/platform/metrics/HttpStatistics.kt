package dev.alllexey.itmowidgets.backend.platform.metrics

import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * The HTTP figures of a statistics period from the [HttpCounters] (docs/contracts/server-statistics.md): four sums of
 * `stat_daily` in a read-only transaction. Daily series hold only the days with a count; the caller zero-fills.
 */
@Service
class HttpStatistics(private val reader: DailyCounterReader) {
    @Transactional(readOnly = true)
    fun stats(period: StatsPeriod): HttpStats {
        val statusByDay = reader.byDayAndDim2(HttpCounters.HTTP, period)
        val latencyByDay = reader.byDayAndDim2(HttpCounters.HTTP_MS, period)
        val statusByRoute = reader.byDim1AndDim2(HttpCounters.HTTP, period)
        val latencyByRoute = reader.byDim1AndDim2(HttpCounters.HTTP_MS, period)
        return HttpStats(
            requests = statusByDay.map { (day, classes) -> DayCount(day, classes.values.sum()) },
            errors5xx = statusByDay.mapNotNull { (day, classes) -> classes[SERVER_ERROR]?.let { DayCount(day, it) } },
            latency = latencyByDay.map { (day, buckets) -> DayLatency(day, buckets.p50(), buckets.p95()) },
            routes = (statusByRoute.keys + latencyByRoute.keys).map { group ->
                val classes = statusByRoute[group].orEmpty()
                val buckets = latencyByRoute[group].orEmpty()
                RouteStats(
                    group = group,
                    requests = classes.values.sum(),
                    errors4xx = classes[CLIENT_ERROR] ?: 0,
                    errors5xx = classes[SERVER_ERROR] ?: 0,
                    p50Millis = buckets.p50(),
                    p95Millis = buckets.p95(),
                )
            }.sortedWith(compareByDescending<RouteStats> { it.requests }.thenBy { it.group }),
        )
    }

    private fun Map<String, Long>.p50() = LatencyPercentiles.millis(this, LatencyPercentiles.MEDIAN)

    private fun Map<String, Long>.p95() = LatencyPercentiles.millis(this, LatencyPercentiles.P95)

    private companion object {
        val CLIENT_ERROR = HttpCounters.statusClass(400)!!
        val SERVER_ERROR = HttpCounters.statusClass(500)!!
    }
}

/**
 * The HTTP answers of a period: [requests] and [errors5xx] per day, the response time per day ([latency]) and the
 * whole period per route group ([routes], by requests descending, then by group). The daily lists hold only days with
 * a count.
 */
data class HttpStats(
    val requests: List<DayCount>,
    val errors5xx: List<DayCount>,
    val latency: List<DayLatency>,
    val routes: List<RouteStats>,
)

/** The median and p95 response time of a day in milliseconds, read from its `http_ms` buckets; null without one. */
data class DayLatency(val day: LocalDate, val p50Millis: Int?, val p95Millis: Int?)

/** One route group over the period: its answers, the 4xx and 5xx among them, and its response time. */
data class RouteStats(
    val group: String,
    val requests: Long,
    val errors4xx: Long,
    val errors5xx: Long,
    val p50Millis: Int?,
    val p95Millis: Int?,
)
