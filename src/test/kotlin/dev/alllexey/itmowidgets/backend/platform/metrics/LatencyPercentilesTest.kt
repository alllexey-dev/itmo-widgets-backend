package dev.alllexey.itmowidgets.backend.platform.metrics

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LatencyPercentilesTest {
    @Test
    fun `an answer falls into the first bucket whose bound it does not exceed`() {
        assertEquals(LatencyBucket.MS_10, LatencyBucket.of(Duration.ZERO))
        assertEquals(LatencyBucket.MS_10, LatencyBucket.of(Duration.ofMillis(10)))
        assertEquals(LatencyBucket.MS_25, LatencyBucket.of(Duration.ofMillis(10).plusNanos(1)))
        assertEquals(LatencyBucket.MS_5000, LatencyBucket.of(Duration.ofMillis(5000)))
        assertEquals(LatencyBucket.INF, LatencyBucket.of(Duration.ofMillis(5001)))
        assertEquals(
            listOf("10", "25", "50", "100", "250", "500", "1000", "2500", "5000", "inf"),
            LatencyBucket.entries.map { it.label },
        )
        assertEquals(LatencyBucket.INF, LatencyBucket.fromLabel("inf"))
        assertNull(LatencyBucket.fromLabel("7"))
    }

    @Test
    fun `a percentile interpolates inside the bucket that holds its rank`() {
        // 100 answers: 50 up to 10 ms, 40 up to 25 ms, 10 up to 50 ms.
        val counts = mapOf("10" to 50L, "25" to 40L, "50" to 10L)

        // Rank 50 is the last of the first bucket: its upper bound.
        assertEquals(10, LatencyPercentiles.millis(counts, LatencyPercentiles.MEDIAN))
        // Rank 95 is the 5th of 10 in (25, 50]: halfway.
        assertEquals(38, LatencyPercentiles.millis(counts, LatencyPercentiles.P95))
        // Rank 25 is half of the first bucket, which starts at 0.
        assertEquals(5, LatencyPercentiles.millis(counts, 0.25))
        assertEquals(50, LatencyPercentiles.millis(counts, 1.0))
    }

    @Test
    fun `empty buckets are skipped and a rank past every bound reads as the highest one`() {
        assertEquals(175, LatencyPercentiles.millis(mapOf("250" to 2L), LatencyPercentiles.MEDIAN))
        assertEquals(5000, LatencyPercentiles.millis(mapOf("10" to 1L, "inf" to 9L), LatencyPercentiles.MEDIAN))
        assertEquals(5000, LatencyPercentiles.millis(mapOf("inf" to 1L), LatencyPercentiles.P95))
    }

    @Test
    fun `nothing timed has no percentile, and unknown labels are ignored`() {
        assertNull(LatencyPercentiles.millis(emptyMap(), LatencyPercentiles.MEDIAN))
        assertNull(LatencyPercentiles.millis(mapOf("10" to 0L, "7" to 5L), LatencyPercentiles.MEDIAN))
        assertEquals(10, LatencyPercentiles.millis(mapOf("10" to 1L, "7" to 5L), LatencyPercentiles.P95))
    }

    @Test
    fun `the quantile is above 0 and at most 1`() {
        for (bad in listOf(0.0, -0.5, 1.5)) {
            assertFailsWith<IllegalArgumentException> { LatencyPercentiles.millis(mapOf("10" to 1L), bad) }
        }
    }
}
