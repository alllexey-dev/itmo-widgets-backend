package dev.alllexey.itmowidgets.backend.services

import java.time.LocalDate
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class AcademicPeriodsTest {
    @Test
    fun `autumn semester runs from September through January of the next calendar year`() {
        assertEquals("2026-1", AcademicPeriods.periodKey(LocalDate.parse("2026-09-01")))
        assertEquals("2026-1", AcademicPeriods.periodKey(LocalDate.parse("2026-12-31")))
        assertEquals("2026-1", AcademicPeriods.periodKey(LocalDate.parse("2027-01-31")))
    }

    @Test
    fun `spring semester runs from February through August and belongs to the previous academic year`() {
        assertEquals("2026-2", AcademicPeriods.periodKey(LocalDate.parse("2027-02-01")))
        assertEquals("2026-2", AcademicPeriods.periodKey(LocalDate.parse("2027-08-31")))
    }
}
