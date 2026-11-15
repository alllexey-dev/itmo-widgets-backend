package dev.alllexey.itmowidgets.backend.feature.activity.persistence

import dev.alllexey.itmowidgets.backend.feature.activity.ActivityPostgreSqlTest
import dev.alllexey.itmowidgets.backend.feature.activity.model.CohortWeekCount
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActiveWeekRepositoryTest @Autowired constructor(private val weeks: ActiveWeekRepository) : ActivityPostgreSqlTest() {
    @Test
    fun `a week is recorded once per account and read back by week`() {
        assertNull(weeks.firstWeek())
        val user = jdbc.insertUser(967101)
        val other = jdbc.insertUser(967102)

        assertTrue(weeks.record(user, W2))
        assertFalse(weeks.record(user, W2))
        assertTrue(weeks.record(other, W2))
        assertTrue(weeks.record(other, W1))

        assertEquals(setOf(user, other), weeks.usersIn(W2).toSet())
        assertEquals(listOf(other), weeks.usersIn(W1))
        assertEquals(emptyList(), weeks.usersIn(W3))
        assertEquals(W1, weeks.firstWeek())
    }

    @Test
    fun `cohorts count their accounts per week from the cohort week on, and a deleted account goes`() {
        val early = jdbc.insertUser(967111)
        val late = jdbc.insertUser(967112)
        val alsoLate = jdbc.insertUser(967113)
        val deleted = jdbc.insertUser(967114)
        val outside = jdbc.insertUser(967115)
        activeIn(early, W1.toString(), W2.toString(), W3.toString())
        activeIn(late, W1.toString(), W2.toString())
        activeIn(alsoLate, W2.toString(), W3.toString())
        activeIn(deleted, W3.toString())
        activeIn(outside, W1.toString(), W2.toString(), W3.toString())
        jdbc.update("DELETE FROM users WHERE id = ?", deleted)

        val counts = weeks.countByCohort(mapOf(early to W1, late to W2, alsoLate to W2, deleted to W2))

        assertEquals(
            setOf(
                CohortWeekCount(W1, W1, 1),
                CohortWeekCount(W1, W2, 1),
                CohortWeekCount(W1, W3, 1),
                CohortWeekCount(W2, W2, 2),
                CohortWeekCount(W2, W3, 1),
            ),
            counts.toSet(),
        )
        assertEquals(emptyList(), weeks.countByCohort(emptyMap()))
    }

    private companion object {
        val W1: LocalDate = LocalDate.parse("2026-11-02")
        val W2: LocalDate = LocalDate.parse("2026-11-09")
        val W3: LocalDate = LocalDate.parse("2026-11-16")
    }
}
