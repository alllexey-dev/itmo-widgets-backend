package dev.alllexey.itmowidgets.backend.platform.metrics

import java.time.Duration

/** The HTTP metrics [HttpCounterFilter] counts (docs/contracts/server-statistics.md). */
object HttpCounters {
    /** Every `/api/` answer: dim1 the route group, dim2 the status class (`2xx`, `3xx`, `4xx`, `5xx`). */
    val HTTP = DailyMetric("http")

    /** Every `/api/` answer but the asynchronous ones: dim1 the route group, dim2 a [LatencyBucket] label. */
    val HTTP_MS = DailyMetric("http_ms")

    /** The route group of a request no route matched. */
    const val OTHER = "other"

    /** The status class of a final status, null for a 1xx one. */
    fun statusClass(status: Int): String? = (status / 100).takeIf { it in 2..5 }?.let { "${it}xx" }
}

/** A latency bucket of `http_ms`: answers that took at most [upperMillis], or longer than every finite bound ([INF]). */
enum class LatencyBucket(val upperMillis: Long?) {
    MS_10(10),
    MS_25(25),
    MS_50(50),
    MS_100(100),
    MS_250(250),
    MS_500(500),
    MS_1000(1000),
    MS_2500(2500),
    MS_5000(5000),
    INF(null),
    ;

    /** The stored dim2: the upper bound in milliseconds, or `inf`. */
    val label: String get() = upperMillis?.toString() ?: "inf"

    companion object {
        fun of(elapsed: Duration): LatencyBucket {
            val nanos = elapsed.toNanos()
            return entries.first { it.upperMillis == null || nanos <= Duration.ofMillis(it.upperMillis).toNanos() }
        }

        fun fromLabel(label: String): LatencyBucket? = entries.firstOrNull { it.label == label }
    }
}
