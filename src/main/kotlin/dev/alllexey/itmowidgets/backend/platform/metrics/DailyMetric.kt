package dev.alllexey.itmowidgets.backend.platform.metrics

/**
 * The name of a daily counter, `stats_daily.metric` (docs/contracts/server-statistics.md). The code that owns an event
 * declares its metric once, as a constant next to the enum of its label values, and uses that constant both to get
 * its [DailyCounter] from [DailyCounters.counter] and to read with [DailyCounterReader]: the name is written in one
 * place, and a name two owners declare fails startup.
 */
@JvmInline
value class DailyMetric(val name: String) {
    init {
        require(NAME.matches(name)) { "A daily metric name is 1-32 characters of [a-z0-9_] starting with a letter" }
    }

    override fun toString(): String = name

    private companion object {
        val NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}
