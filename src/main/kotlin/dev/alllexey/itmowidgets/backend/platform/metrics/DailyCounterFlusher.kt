package dev.alllexey.itmowidgets.backend.platform.metrics

import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Moves the pending counts of [DailyCounters] into `stats_daily` once a minute and once more on shutdown, after the web
 * server stopped taking requests, so a crash loses at most the last minute. A failed write puts the counts back for the
 * next flush. The flush runs on a thread of its own: the shared `@Scheduled` thread can be held by a long job for
 * minutes, which would stretch what a crash loses.
 */
@Component
class DailyCounterFlusher(private val counters: DailyCounters, private val store: DailyCounterStore) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "daily-counters").apply { isDaemon = true }
    }

    @PostConstruct
    fun start() {
        scheduler.scheduleWithFixedDelay(::flush, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS)
    }

    fun flush() {
        val counts = counters.drain()
        if (counts.isEmpty()) return
        try {
            store.add(counts)
        } catch (error: Exception) {
            counters.restore(counts)
            log.warn("Daily counters not written, kept for the next flush: {}", SafeDiagnostics.describe(error), error)
        }
    }

    @PreDestroy
    fun stop() {
        scheduler.shutdown()
        scheduler.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
        flush()
    }

    private companion object {
        const val PERIOD_SECONDS = 60L
        const val SHUTDOWN_WAIT_SECONDS = 10L
        val log = LoggerFactory.getLogger(DailyCounterFlusher::class.java)
    }
}
