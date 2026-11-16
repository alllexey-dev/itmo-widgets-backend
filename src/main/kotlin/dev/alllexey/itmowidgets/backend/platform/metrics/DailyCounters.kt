package dev.alllexey.itmowidgets.backend.platform.metrics

import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** One row of `stats_daily`: a Europe/Moscow [day], a [metric] and its two labels. */
data class DailyCounterKey(val day: LocalDate, val metric: DailyMetric, val dim1: String, val dim2: String)

/**
 * The write side of the daily counters (docs/contracts/server-statistics.md). Counts add up in memory per
 * [DailyCounterKey]; [DailyCounterFlusher] moves them into `stats_daily`, so counting never waits for the database.
 * Platform knows no feature: each owner declares its [DailyMetric] here once, at construction, and passes its labels
 * in; a label outside [DIMENSION] (dim1 also not empty) is dropped with a warning.
 */
@Component
class DailyCounters(private val clock: Clock) {
    private val declared = ConcurrentHashMap.newKeySet<DailyMetric>()
    private val pending = ConcurrentHashMap<DailyCounterKey, Long>()

    /** The counter of [metric] for its one owner; a second declaration of the same name fails, so at startup. */
    fun counter(metric: DailyMetric): DailyCounter {
        check(declared.add(metric)) { "Daily metric $metric is declared twice" }
        return DailyCounter(this, metric)
    }

    /**
     * Takes every pending count out, each key atomically, so a concurrent increment lands either in the result or in
     * the next drain, never in neither.
     */
    internal fun drain(): Map<DailyCounterKey, Long> = buildMap {
        for (key in pending.keys) pending.remove(key)?.let { put(key, it) }
    }

    /** Puts counts a failed write took out back, to be written with the next flush. */
    internal fun restore(counts: Map<DailyCounterKey, Long>) = counts.forEach(::add)

    internal fun record(metric: DailyMetric, dim1: String, dim2: String, amount: Long) {
        try {
            require(amount > 0) { "A count is positive" }
            // The values stay out of the message: a wrong label is a bug, and its value may be anything.
            require(dim1.isNotEmpty() && DIMENSION.matches(dim1) && DIMENSION.matches(dim2)) { "A label is outside $DIMENSION" }
            val key = DailyCounterKey(LocalDate.ofInstant(clock.instant(), ZONE), metric, dim1, dim2)
            if (TransactionSynchronizationManager.isActualTransactionActive() &&
                TransactionSynchronizationManager.isSynchronizationActive()
            ) {
                // A transactional event happened only once its transaction commits; a rollback counts nothing.
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun afterCommit() = add(key, amount)
                    },
                )
            } else {
                add(key, amount)
            }
        } catch (error: Exception) {
            log.warn("Daily counter {} not incremented: {}", metric, SafeDiagnostics.describe(error), error)
        }
    }

    private fun add(key: DailyCounterKey, amount: Long) {
        pending.merge(key, amount, Long::plus)
    }

    companion object {
        /** The day of a count is the Europe/Moscow date at the moment it is counted. */
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")

        /** A label: up to 64 characters of letters, digits, `.`, `_` and `-`; dim1 is never empty. */
        val DIMENSION = Regex("^[A-Za-z0-9._-]{0,64}$")

        private val log = LoggerFactory.getLogger(DailyCounters::class.java)
    }
}

/** The handle an owner holds for its [metric]; see [DailyCounters]. */
class DailyCounter internal constructor(private val counters: DailyCounters, val metric: DailyMetric) {
    /**
     * Adds [amount] under [dim1] and [dim2] for today in Europe/Moscow, taken from the clock now. Inside a transaction
     * it counts after the commit and not at all on a rollback; outside one, at once. Safe from any thread; never throws
     * and never touches the database.
     */
    fun increment(dim1: String, dim2: String = "", amount: Long = 1) = counters.record(metric, dim1, dim2, amount)
}
