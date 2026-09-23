package dev.alllexey.itmowidgets.backend.services

import java.time.LocalDate
import java.time.Month

/** Period key `YYYY-S`: the academic year's start and 1 for September–January, 2 for February–August. */
object AcademicPeriods {
    fun periodKey(date: LocalDate): String = when {
        date.month >= Month.SEPTEMBER -> "${date.year}-1"
        date.month == Month.JANUARY -> "${date.year - 1}-1"
        else -> "${date.year - 1}-2"
    }
}
