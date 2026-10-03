package dev.alllexey.itmowidgets.backend.feature.reviews.service

import java.time.LocalDate
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ReviewDatesTest {
    @Test
    fun `a date with time keeps only the date`() {
        assertEquals(ParsedReviewDate(LocalDate.of(2025, 1, 25), null), ReviewDates.parse("12:18 25.01.2025"))
        assertEquals(ParsedReviewDate(LocalDate.of(2026, 7, 7), null), ReviewDates.parse("1:15 07.07.2026"))
    }

    @Test
    fun `a before year date keeps only the year`() {
        assertEquals(ParsedReviewDate(null, 2024), ReviewDates.parse("до 2024"))
    }

    @Test
    fun `unknown missing and impossible dates keep neither field`() {
        for (raw in listOf("", "вчера", "12:18 31.02.2025", "25.01.2025")) {
            assertEquals(ParsedReviewDate(null, null), ReviewDates.parse(raw), raw)
        }
    }
}
