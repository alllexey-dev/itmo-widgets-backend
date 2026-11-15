package dev.alllexey.itmowidgets.backend.platform.metrics

import dev.alllexey.itmowidgets.backend.testing.MutableClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DailyCountersTest {
    private val clock = MutableClock().apply { now = Instant.parse("2026-11-14T20:59:00Z") }
    private val counters = DailyCounters(clock)
    private val counter = counters.counter(METRIC)

    @AfterEach
    fun endTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(false)
    }

    @Test
    fun `counts add up per Moscow day and labels until drained`() {
        counter.increment("A", "x")
        counter.increment("A", "x", 4)
        counter.increment("A")
        // 21:00 UTC is midnight in Moscow: the next day.
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        counter.increment("A", "x")

        assertEquals(
            mapOf(key(TODAY, "A", "x") to 5L, key(TODAY, "A", "") to 1L, key(TODAY.plusDays(1), "A", "x") to 1L),
            counters.drain(),
        )
        assertTrue(counters.drain().isEmpty())
    }

    @Test
    fun `restored counts join the next drain`() {
        counter.increment("A")
        val taken = counters.drain()
        counter.increment("A", amount = 2)

        counters.restore(taken)

        assertEquals(mapOf(key(TODAY, "A", "") to 3L), counters.drain())
    }

    @Test
    fun `a metric is declared once and a name is checked`() {
        assertFailsWith<IllegalStateException> { counters.counter(DailyMetric("test_counter")) }
        for (bad in listOf("", "Http", "1st", "with space", "a".repeat(33))) {
            assertFailsWith<IllegalArgumentException>(bad) { DailyMetric(bad) }
        }
        DailyMetric("a".repeat(32))
    }

    @Test
    fun `a label outside the pattern or a count below one is dropped without throwing`() {
        counter.increment("")
        counter.increment("a/b")
        counter.increment("x".repeat(65))
        counter.increment("A", "with space")
        counter.increment("A", amount = 0)
        counter.increment("A.b_c-1", "x".repeat(64))

        assertEquals(mapOf(key(TODAY, "A.b_c-1", "x".repeat(64)) to 1L), counters.drain())
    }

    @Test
    fun `inside a transaction a count waits for the commit and a rollback drops it`() {
        begin()
        counter.increment("COMMITTED")
        assertTrue(counters.drain().isEmpty())
        TransactionSynchronizationUtils.triggerAfterCommit()
        endTransaction()

        begin()
        counter.increment("ROLLED_BACK")
        TransactionSynchronizationUtils.invokeAfterCompletion(
            TransactionSynchronizationManager.getSynchronizations(),
            TransactionSynchronization.STATUS_ROLLED_BACK,
        )
        endTransaction()

        assertEquals(mapOf(key(TODAY, "COMMITTED", "") to 1L), counters.drain())
    }

    @Test
    fun `synchronization without a transaction counts at once`() {
        TransactionSynchronizationManager.initSynchronization()

        counter.increment("A")

        assertEquals(mapOf(key(TODAY, "A", "") to 1L), counters.drain())
    }

    @Test
    fun `concurrent increments and drains lose and duplicate nothing`() {
        val threads = 8
        val perThread = 20_000
        val pool = Executors.newFixedThreadPool(threads + 1)
        val start = CountDownLatch(1)
        val drained = mutableListOf<Map<DailyCounterKey, Long>>()
        try {
            val writers = (1..threads).map { thread ->
                pool.submit {
                    start.await()
                    repeat(perThread) { counter.increment("T${thread % 2}", "x") }
                }
            }
            val drainer = pool.submit {
                start.await()
                while (writers.any { !it.isDone }) drained.add(counters.drain())
            }
            start.countDown()
            writers.forEach { it.get(30, TimeUnit.SECONDS) }
            drainer.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        drained.add(counters.drain())

        val totals = drained.flatMap { it.entries }.groupBy({ it.key.dim1 }, { it.value }).mapValues { it.value.sum() }
        assertEquals(mapOf("T0" to threads / 2L * perThread, "T1" to threads / 2L * perThread), totals)
    }

    private fun begin() {
        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
    }

    private fun key(day: LocalDate, dim1: String, dim2: String) = DailyCounterKey(day, METRIC, dim1, dim2)

    private companion object {
        val METRIC = DailyMetric("test_counter")
        val TODAY: LocalDate = LocalDate.parse("2026-11-14")
    }
}
