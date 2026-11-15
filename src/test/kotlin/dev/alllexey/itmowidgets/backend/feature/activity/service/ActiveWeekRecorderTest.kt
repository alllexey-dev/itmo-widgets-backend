package dev.alllexey.itmowidgets.backend.feature.activity.service

import dev.alllexey.itmowidgets.backend.feature.activity.persistence.ActiveWeekRepository
import dev.alllexey.itmowidgets.backend.testing.MutableClock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertFailsWith

class ActiveWeekRecorderTest {
    private val weeks = mock(ActiveWeekRepository::class.java)
    private val clock = MutableClock()
    private val user = UUID.randomUUID()
    private val other = UUID.randomUUID()

    @BeforeEach
    fun start() {
        clock.now = WEDNESDAY
        `when`(weeks.usersIn(WEEK)).thenReturn(emptyList())
        `when`(weeks.usersIn(NEXT_WEEK)).thenReturn(emptyList())
    }

    @Test
    fun `an account reaches the database once per week`() {
        val recorder = ActiveWeekRecorder(weeks, clock)

        repeat(3) { recorder.record(user) }
        recorder.record(other)
        clock.now = SUNDAY_LATE
        recorder.record(user)

        verify(weeks).usersIn(WEEK)
        verify(weeks).record(user, WEEK)
        verify(weeks).record(other, WEEK)
    }

    @Test
    fun `a new Moscow week starts at Monday midnight and records the account again`() {
        val recorder = ActiveWeekRecorder(weeks, clock)
        clock.now = SUNDAY_LATE
        recorder.record(user)
        clock.now = MONDAY_EARLY
        recorder.record(user)
        recorder.record(user)

        verify(weeks).record(user, WEEK)
        verify(weeks).record(user, NEXT_WEEK)
        verify(weeks).usersIn(NEXT_WEEK)
    }

    @Test
    fun `after a restart the accounts already recorded this week are not written again`() {
        `when`(weeks.usersIn(WEEK)).thenReturn(listOf(user))
        val restarted = ActiveWeekRecorder(weeks, clock)

        restarted.record(user)
        restarted.record(other)

        verify(weeks, never()).record(user, WEEK)
        verify(weeks).record(other, WEEK)
    }

    @Test
    fun `a failed write is tried again on the next request`() {
        val recorder = ActiveWeekRecorder(weeks, clock)
        `when`(weeks.record(user, WEEK)).thenThrow(DataAccessResourceFailureException("synthetic")).thenReturn(true)

        assertFailsWith<DataAccessResourceFailureException> { recorder.record(user) }
        recorder.record(user)
        recorder.record(user)

        verify(weeks, times(2)).record(user, WEEK)
    }

    @Test
    fun `a request that read the clock before a new week began is written without reloading the past week`() {
        val recorder = ActiveWeekRecorder(weeks, clock)
        clock.now = MONDAY_EARLY
        recorder.record(user)
        clock.now = SUNDAY_LATE
        recorder.record(other)
        clock.now = MONDAY_EARLY
        recorder.record(user)

        verify(weeks, never()).usersIn(WEEK)
        verify(weeks).record(other, WEEK)
        verify(weeks).record(user, NEXT_WEEK)
    }

    private companion object {
        val WEEK: LocalDate = LocalDate.parse("2026-11-09")
        val NEXT_WEEK: LocalDate = LocalDate.parse("2026-11-16")
        val WEDNESDAY: Instant = Instant.parse("2026-11-11T09:00:00Z")

        /** Sunday 23:59 in Moscow, Sunday 20:59 UTC. */
        val SUNDAY_LATE: Instant = Instant.parse("2026-11-15T20:59:00Z")

        /** Monday 00:00 in Moscow, still Sunday in UTC. */
        val MONDAY_EARLY: Instant = Instant.parse("2026-11-15T21:00:00Z")
    }
}
