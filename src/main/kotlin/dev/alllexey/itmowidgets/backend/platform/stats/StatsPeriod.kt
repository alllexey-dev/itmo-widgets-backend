package dev.alllexey.itmowidgets.backend.platform.stats

import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The period of a statistics answer: the Europe/Moscow calendar days [from] to [to], both inclusive, at most
 * [MAX_DAYS] of them. A query selects its rows by `[startInstant, endInstantExclusive)` and groups them into days of
 * [zone]; a daily series has one row per day of [days].
 */
data class StatsPeriod(val from: LocalDate, val to: LocalDate) {
    init {
        if (from.isAfter(to)) throw InvalidRequestDataException("from is after to")
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) throw InvalidRequestDataException("The period is over $MAX_DAYS days")
    }

    val zone: ZoneId get() = ZONE

    /** Every day of the period, oldest first. */
    val days: List<LocalDate> get() = from.datesUntil(to.plusDays(1)).toList()

    /** The start of [from] in [zone]. */
    val startInstant: Instant get() = from.atStartOfDay(ZONE).toInstant()

    /** The start of the day after [to] in [zone]. */
    val endInstantExclusive: Instant get() = to.plusDays(1).atStartOfDay(ZONE).toInstant()

    /**
     * [counts] as one row per day of the period, oldest first; a day without a row counts 0. Each day may appear once
     * and only inside the period: anything else is a query bug, not data.
     */
    fun zeroFilled(counts: Collection<DayCount>): List<DayCount> {
        val byDay = counts.associate { it.day to it.count }
        require(byDay.size == counts.size) { "A day appears twice in a daily series" }
        require(byDay.keys.all { !it.isBefore(from) && !it.isAfter(to) }) { "A daily series has a day outside $from..$to" }
        return days.map { DayCount(it, byDay[it] ?: 0) }
    }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")

        /** The default period: today and the 29 days before it. */
        const val DEFAULT_DAYS = 30L

        const val MAX_DAYS = 1100L

        /**
         * The period a request asks for with the optional `from` and `to` query parameters. [to] defaults to today in
         * [ZONE], [from] to [to] minus 29 days. [to] after today, [from] after [to] or more than [MAX_DAYS] days are
         * [InvalidRequestDataException].
         */
        fun requested(from: LocalDate?, to: LocalDate?, clock: Clock): StatsPeriod {
            val today = LocalDate.ofInstant(clock.instant(), ZONE)
            val end = to ?: today
            if (end.isAfter(today)) throw InvalidRequestDataException("to is in the future")
            return StatsPeriod(from ?: end.minusDays(DEFAULT_DAYS - 1), end)
        }
    }
}

/** One day of a daily series and its count. */
data class DayCount(val day: LocalDate, val count: Long)
