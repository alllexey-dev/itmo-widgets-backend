package dev.alllexey.itmowidgets.backend.feature.sport.service

import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SportLessonEndTest {
    @Test
    fun `a weekly series ending two days before it starts ends on its start date at the slot end`() {
        // The observed shape: Thursday 19:00 MSK with date_end on Tuesday 21:00 MSK, sent in UTC.
        assertEquals(
            time("2026-10-15T18:00Z"),
            normalizedLessonEnd(time("2026-10-15T16:00Z"), time("2026-10-13T18:00Z"), "21:00"),
        )
        assertEquals(
            time("2026-10-22T18:00Z"),
            normalizedLessonEnd(time("2026-10-22T16:00Z"), time("2026-10-20T18:00Z"), "21:00"),
        )
    }

    @Test
    fun `a consistent end is returned unchanged even when the slot disagrees`() {
        val raw = time("2026-10-05T08:30Z")
        assertEquals(raw, normalizedLessonEnd(time("2026-10-05T07:00Z"), raw, "11:45"))
    }

    @Test
    fun `without a usable slot end the raw time of day is moved to the start date`() {
        assertEquals(
            time("2026-10-15T17:30Z"),
            normalizedLessonEnd(time("2026-10-15T16:00Z"), time("2026-10-13T17:30Z"), null),
        )
        assertEquals(
            time("2026-10-15T17:30Z"),
            normalizedLessonEnd(time("2026-10-15T16:00Z"), time("2026-10-13T17:30Z"), "  "),
        )
    }

    @Test
    fun `an overnight or insane end has no normalized end`() {
        // 23:00 to 01:00 MSK would be rebuilt before its start.
        assertNull(normalizedLessonEnd(time("2026-10-15T20:00Z"), time("2026-10-15T22:00Z"), "01:00"))
        assertNull(normalizedLessonEnd(time("2026-10-15T16:00Z"), time("2026-10-13T16:05Z"), "19:05"))
        assertNull(normalizedLessonEnd(time("2026-10-15T05:00Z"), time("2026-10-13T18:00Z"), "21:00"))
        assertNull(normalizedLessonEnd(time("2026-10-15T16:00Z"), time("2026-10-15T16:00Z"), null))
    }

    @Test
    fun `the day is the Moscow date of the start, not its UTC date`() {
        // 00:30 MSK on the 16th is 21:30 UTC on the 15th.
        val start = time("2026-10-15T21:30Z")
        val consistent = time("2026-10-15T23:00Z")
        assertEquals(consistent, normalizedLessonEnd(start, consistent, "02:00"))
        assertEquals(time("2026-10-15T23:00Z"), normalizedLessonEnd(start, time("2026-10-13T23:00Z"), "02:00"))
    }

    private fun time(text: String): OffsetDateTime = OffsetDateTime.parse(text)
}
