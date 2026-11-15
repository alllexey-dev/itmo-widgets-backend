package dev.alllexey.itmowidgets.backend.feature.activity.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Days and weeks of activity: Europe/Moscow calendar days, weeks from Monday, named by their Monday. */
object ActivityCalendar {
    val ZONE: ZoneId = ZoneId.of("Europe/Moscow")

    fun dayOf(at: Instant): LocalDate = LocalDate.ofInstant(at, ZONE)

    /** The Monday of the week [day] is in. */
    fun weekOf(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun weekOf(at: Instant): LocalDate = weekOf(dayOf(at))

    /** The first Monday on or after [day]. */
    fun firstWeekFrom(day: LocalDate): LocalDate = day.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))

    /** The instant [day] starts. */
    fun startOf(day: LocalDate): Instant = day.atStartOfDay(ZONE).toInstant()
}
