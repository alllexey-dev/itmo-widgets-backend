package dev.alllexey.itmowidgets.backend.services

import java.time.DateTimeException
import java.time.LocalDate

/** At most one of the fields is set; both are null when the provider's date has no known form. */
data class ParsedReviewDate(val writtenOn: LocalDate?, val writtenBeforeYear: Int?)

object ReviewDates {
    private val DATE_TIME = Regex("""^\d{1,2}:\d{2} (\d{2})\.(\d{2})\.(\d{4})$""")
    private val BEFORE_YEAR = Regex("""^до (\d{4})$""")

    /** `12:18 25.01.2025` keeps only the date, `до 2024` keeps the year; the time is dropped. */
    fun parse(raw: String): ParsedReviewDate {
        DATE_TIME.matchEntire(raw)?.let { match ->
            val (day, month, year) = match.destructured
            val date = try {
                LocalDate.of(year.toInt(), month.toInt(), day.toInt())
            } catch (_: DateTimeException) {
                null
            }
            return ParsedReviewDate(date, null)
        }
        BEFORE_YEAR.matchEntire(raw)?.let { return ParsedReviewDate(null, it.groupValues[1].toInt()) }
        return ParsedReviewDate(null, null)
    }
}
