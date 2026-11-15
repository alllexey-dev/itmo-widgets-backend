package dev.alllexey.itmowidgets.backend.platform.stats

import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StatsPeriodTest {
    // 23:30 UTC on March 14 is already March 15 in Moscow (UTC+3).
    private val clock = Clock.fixed(Instant.parse("2031-03-14T23:30:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.parse("2031-03-15")

    @Test
    fun `defaults are the 30 Moscow days ending today`() {
        val period = StatsPeriod.requested(null, null, clock)
        assertEquals(StatsPeriod(today.minusDays(29), today), period)
        assertEquals(30, period.days.size)
        assertEquals(today.minusDays(29), period.days.first())
        assertEquals(today, period.days.last())
    }

    @Test
    fun `a missing bound defaults on its own`() {
        val to = LocalDate.parse("2031-01-31")
        assertEquals(StatsPeriod(LocalDate.parse("2031-01-02"), to), StatsPeriod.requested(null, to, clock))
        val from = LocalDate.parse("2031-03-01")
        assertEquals(StatsPeriod(from, today), StatsPeriod.requested(from, null, clock))
        assertEquals(StatsPeriod(today, today), StatsPeriod.requested(today, today, clock))
    }

    @Test
    fun `a future end, a reversed period and more than 1100 days are invalid data`() {
        assertFailsWith<InvalidRequestDataException> { StatsPeriod.requested(null, today.plusDays(1), clock) }
        assertFailsWith<InvalidRequestDataException> { StatsPeriod.requested(today, today.minusDays(1), clock) }
        assertFailsWith<InvalidRequestDataException> { StatsPeriod.requested(today.plusDays(1), null, clock) }
        assertEquals(1100, StatsPeriod.requested(today.minusDays(1099), today, clock).days.size)
        assertFailsWith<InvalidRequestDataException> { StatsPeriod.requested(today.minusDays(1100), today, clock) }
    }

    @Test
    fun `the instants are the Moscow midnights around the period`() {
        val period = StatsPeriod(LocalDate.parse("2031-03-01"), LocalDate.parse("2031-03-02"))
        assertEquals(Instant.parse("2031-02-28T21:00:00Z"), period.startInstant)
        assertEquals(Instant.parse("2031-03-02T21:00:00Z"), period.endInstantExclusive)
        assertEquals("Europe/Moscow", period.zone.id)
    }

    @Test
    fun `a daily series is zero filled oldest first`() {
        val period = StatsPeriod(LocalDate.parse("2031-03-01"), LocalDate.parse("2031-03-04"))
        val series = period.zeroFilled(listOf(DayCount(LocalDate.parse("2031-03-03"), 5), DayCount(LocalDate.parse("2031-03-01"), 2)))
        assertEquals(listOf(2L, 0L, 5L, 0L), series.map { it.count })
        assertEquals(period.days, series.map { it.day })
        assertEquals(listOf(0L, 0L, 0L, 0L), period.zeroFilled(emptyList()).map { it.count })
    }

    @Test
    fun `a daily series with a repeated day or a day outside the period is a bug`() {
        val period = StatsPeriod(LocalDate.parse("2031-03-01"), LocalDate.parse("2031-03-04"))
        val day = LocalDate.parse("2031-03-02")
        assertFailsWith<IllegalArgumentException> { period.zeroFilled(listOf(DayCount(day, 1), DayCount(day, 2))) }
        assertFailsWith<IllegalArgumentException> { period.zeroFilled(listOf(DayCount(LocalDate.parse("2031-03-05"), 1))) }
        assertFailsWith<IllegalArgumentException> { period.zeroFilled(listOf(DayCount(LocalDate.parse("2031-02-28"), 1))) }
    }
}
