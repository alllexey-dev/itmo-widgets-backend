package dev.alllexey.itmowidgets.backend.platform.metrics

import dev.alllexey.itmowidgets.backend.testing.TestClock
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.dao.QueryTimeoutException
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DailyCounterFlusherTest {
    private val counters = DailyCounters(TestClock.CLOCK)
    private val counter = counters.counter(DailyMetric("test_flush"))
    private val store = mock(DailyCounterStore::class.java)
    private val flusher = DailyCounterFlusher(counters, store)

    @Test
    fun `a flush writes what is pending and leaves nothing behind`() {
        counter.increment("A", amount = 3)

        flusher.flush()

        verify(store).add(mapOf(KEY to 3L))
        assertTrue(counters.drain().isEmpty())
    }

    @Test
    fun `nothing pending writes nothing`() {
        flusher.flush()

        verify(store, never()).add(anyMap())
    }

    @Test
    fun `a failed write keeps the counts for the next flush`() {
        counter.increment("A", amount = 3)
        doThrow(QueryTimeoutException("synthetic outage")).`when`(store).add(mapOf(KEY to 3L))

        flusher.flush()
        counter.increment("A")

        assertEquals(mapOf(KEY to 4L), counters.drain())
    }

    @Test
    fun `shutdown flushes what is left`() {
        flusher.start()
        counter.increment("A", amount = 3)

        flusher.stop()

        verify(store).add(mapOf(KEY to 3L))
    }

    private companion object {
        val KEY = DailyCounterKey(LocalDate.parse("2026-09-01"), DailyMetric("test_flush"), "A", "")
    }
}
