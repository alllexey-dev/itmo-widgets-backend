package dev.alllexey.itmowidgets.backend.platform.metrics

import kotlin.math.roundToInt

/**
 * Percentiles of response time from `http_ms` bucket counts, the way Prometheus' `histogram_quantile` reads a
 * histogram: find the bucket that holds the rank and interpolate linearly between its bounds (the first bucket starts
 * at 0). A rank in [LatencyBucket.INF] has no upper bound and reads as the highest finite one, 5000. So a value is as
 * precise as its bucket is wide.
 */
object LatencyPercentiles {
    const val MEDIAN = 0.5
    const val P95 = 0.95

    /**
     * The [quantile] (in (0, 1]) of [counts], `http_ms` sums by dim2 as [DailyCounterReader] returns them; labels that
     * are no [LatencyBucket] are ignored. Null when nothing was timed.
     */
    fun millis(counts: Map<String, Long>, quantile: Double): Int? {
        require(quantile > 0 && quantile <= 1) { "quantile must be in (0, 1]" }
        val byBucket = counts.mapNotNull { (label, count) -> LatencyBucket.fromLabel(label)?.let { it to count } }.toMap()
        val total = byBucket.values.sum()
        if (total <= 0) return null
        val rank = quantile * total
        var below = 0L
        var lower = 0L
        for (bucket in LatencyBucket.entries) {
            val upper = bucket.upperMillis ?: break
            val count = byBucket[bucket] ?: 0
            if (count > 0 && below + count >= rank) {
                return (lower + (upper - lower) * (rank - below) / count).roundToInt()
            }
            below += count
            lower = upper
        }
        // The rank is past every finite bound.
        return lower.toInt()
    }
}
