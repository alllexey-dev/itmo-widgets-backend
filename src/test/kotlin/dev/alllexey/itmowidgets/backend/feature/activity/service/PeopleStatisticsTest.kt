package dev.alllexey.itmowidgets.backend.feature.activity.service

import dev.alllexey.itmowidgets.backend.feature.activity.ActivityPostgreSqlTest
import dev.alllexey.itmowidgets.backend.feature.activity.model.ActivePeople
import dev.alllexey.itmowidgets.backend.feature.activity.model.ClassicCohort
import dev.alllexey.itmowidgets.backend.feature.activity.model.PeopleDay
import dev.alllexey.itmowidgets.backend.feature.activity.model.RetentionCohort
import dev.alllexey.itmowidgets.backend.feature.activity.persistence.PeopleDailyRepository
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PeopleStatisticsTest @Autowired constructor(
    private val statistics: PeopleStatistics,
    private val days: PeopleDailyRepository,
    private val job: PeopleDailyJob,
) : ActivityPostgreSqlTest() {
    @BeforeEach
    fun now() {
        clock.now = NOW
    }

    @Test
    fun `active people count each person once by the latest activity, in rolling windows`() {
        val before = statistics.people(TODAY, TODAY)
        val today = jdbc.insertUser(967201)
        val thisWeek = jdbc.insertUser(967202)
        val thisMonth = jdbc.insertUser(967203)
        val edge = jdbc.insertUser(967204)
        val gone = jdbc.insertUser(967205)
        clientSeen(today, NOW.minus(Duration.ofHours(2)))
        deviceLogin(today, NOW.minus(Duration.ofDays(10)))
        connectionUsed(today, NOW.minus(Duration.ofDays(3)))
        deviceLogin(thisWeek, NOW.minus(Duration.ofDays(3)))
        connectionUsed(thisMonth, NOW.minus(Duration.ofDays(20)))
        deviceLogin(edge, NOW.minus(Duration.ofDays(30)))
        clientSeen(gone, NOW.minus(Duration.ofDays(30)).minusSeconds(1))

        val after = statistics.people(TODAY, TODAY)

        assertEquals(
            ActivePeople(before.active.active1d + 1, before.active.active7d + 2, before.active.active30d + 4),
            after.active,
        )
        assertEquals(before.activeIn30Days + setOf(today, thisWeek, thisMonth, edge), after.activeIn30Days)
        assertEquals(after.active.active30d, after.activeIn30Days.size.toLong())
    }

    @Test
    fun `the history is the nightly rows of the last 90 days, oldest first`() {
        val row = ActivePeople(1, 2, 3)
        for (day in listOf(TODAY.minusDays(91), TODAY.minusDays(90), TODAY.minusDays(1), TODAY.minusDays(40))) days.insert(day, row)

        assertEquals(
            listOf(PeopleDay(TODAY.minusDays(90), row), PeopleDay(TODAY.minusDays(40), row), PeopleDay(TODAY.minusDays(1), row)),
            statistics.people(TODAY, TODAY).history,
        )
    }

    @Test
    fun `cohorts follow Moscow registration weeks, are filled only as far as the whole cohort lived, and forget deleted accounts`() {
        val world = cohortWorld()

        val stats = statistics.people(LocalDate.parse("2026-10-19"), TODAY)

        assertEquals(
            listOf(
                RetentionCohort(LocalDate.parse("2026-10-19"), 2, listOf(1, 1, 0)),
                RetentionCohort(LocalDate.parse("2026-10-26"), 1, listOf(1, 0)),
                RetentionCohort(LocalDate.parse("2026-11-02"), 0, listOf(0)),
                // Its youngest account could have registered two and a half days ago: too young for week 1.
                RetentionCohort(LocalDate.parse("2026-11-09"), 1, emptyList()),
                RetentionCohort(LocalDate.parse("2026-11-16"), 0, emptyList()),
            ),
            stats.retention,
        )
        assertEquals(
            listOf(
                // Collection started in the week of 2026-11-02: the weeks before it are unknown, not zero.
                ClassicCohort(LocalDate.parse("2026-10-19"), 2, listOf(null, null, 1, 1)),
                ClassicCohort(LocalDate.parse("2026-10-26"), 1, listOf(null, 1, 0)),
                ClassicCohort(LocalDate.parse("2026-11-02"), 0, listOf(0, 0)),
                ClassicCohort(LocalDate.parse("2026-11-09"), 1, listOf(1)),
                // The current week is not finished.
                ClassicCohort(LocalDate.parse("2026-11-16"), 0, emptyList()),
            ),
            stats.classicRetention,
        )
        assertTrue(world.deleted !in stats.activeIn30Days)
        // A cohort week whose Monday is before the period is not shown, even when the period starts in that week.
        assertEquals(LocalDate.parse("2026-10-19"), statistics.people(LocalDate.parse("2026-10-13"), TODAY).retention.first().weekStart)
    }

    @Test
    fun `at most 26 cohorts, the the latest, with retention stopping at week 12 and classic retention not stopping`() {
        val stats = statistics.people(LocalDate.parse("2026-01-01"), TODAY)

        assertEquals(26, stats.retention.size)
        assertEquals(LocalDate.parse("2026-05-25"), stats.retention.first().weekStart)
        assertEquals(LocalDate.parse("2026-11-16"), stats.retention.last().weekStart)
        assertEquals(12, stats.retention.first().retained.size)
        assertEquals(25, stats.classicRetention.first().active.size)
        assertTrue(stats.classicRetention.first().active.all { it == null }, "Nothing collected yet")
        assertEquals(stats.retention.map { it.weekStart }, stats.classicRetention.map { it.weekStart })
        assertEquals(emptyList(), statistics.people(LocalDate.parse("2026-11-17"), TODAY).retention)
        assertFailsWith<IllegalArgumentException> { statistics.people(TODAY, TODAY.minusDays(1)) }
    }

    @Test
    fun `the nightly job writes yesterday's row once, and a rerun changes nothing`() {
        val user = jdbc.insertUser(967301)
        clientSeen(user, NOW.minus(Duration.ofHours(1)))
        clock.now = NIGHT
        val expected = statistics.people(TODAY, TODAY).active

        job.nightly()
        val other = jdbc.insertUser(967302)
        deviceLogin(other, NIGHT)
        clock.now = NIGHT.plus(Duration.ofHours(10))
        job.nightly()

        assertEquals(listOf(PeopleDay(TODAY, expected)), days.since(TODAY))
        assertTrue(expected.active1d >= 1)

        clock.now = NIGHT.plus(Duration.ofDays(1))
        job.nightly()
        assertEquals(listOf(TODAY, TODAY.plusDays(1)), days.since(TODAY).map { it.day })
    }

    private class CohortWorld(val deleted: UUID)

    private fun cohortWorld(): CohortWorld {
        // Monday 00:30 Moscow, still Sunday in UTC: the week of 2026-10-19.
        val mondayEarly = jdbc.insertUser(967401, createdAt = Instant.parse("2026-10-18T21:30:00Z"))
        // Sunday 23:30 Moscow: the last minutes of the same week.
        jdbc.insertUser(967402, createdAt = Instant.parse("2026-10-25T20:30:00Z"))
        // Sunday 23:30 Moscow a week earlier: the week of 2026-10-12, before the period.
        val before = jdbc.insertUser(967403, createdAt = Instant.parse("2026-10-18T20:30:00Z"))
        val deleted = jdbc.insertUser(967404, createdAt = Instant.parse("2026-10-20T09:00:00Z"))
        val young = jdbc.insertUser(967405, createdAt = Instant.parse("2026-11-10T09:00:00Z"))
        val second = jdbc.insertUser(967406, createdAt = Instant.parse("2026-10-27T09:00:00Z"))

        // Back in its third week, gone since: retained in weeks 1 and 2 (unbounded), not in week 3.
        clientSeen(mondayEarly, Instant.parse("2026-11-03T09:00:00Z"))
        clientSeen(before, NOW)
        clientSeen(deleted, NOW)
        clientSeen(young, NOW)
        deviceLogin(second, Instant.parse("2026-11-04T09:00:00Z"))

        activeIn(mondayEarly, "2026-11-02", "2026-11-09", "2026-11-16")
        activeIn(second, "2026-11-02")
        activeIn(young, "2026-11-09", "2026-11-16")
        activeIn(before, "2026-11-02")
        activeIn(deleted, "2026-11-02", "2026-11-09")
        jdbc.update("DELETE FROM users WHERE id = ?", deleted)
        return CohortWorld(deleted)
    }

    private companion object {
        /** Wednesday 12:00 in Moscow; the current week is the one of 2026-11-16. */
        val NOW: Instant = Instant.parse("2026-11-18T09:00:00Z")
        val TODAY: LocalDate = LocalDate.parse("2026-11-18")

        /** 00:20 in Moscow on the day after [TODAY]. */
        val NIGHT: Instant = Instant.parse("2026-11-18T21:20:00Z")
    }
}
