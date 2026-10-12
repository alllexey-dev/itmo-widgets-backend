package dev.alllexey.itmowidgets.backend.feature.agent.service

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentEventRow
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.security.AgentAuthentication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The long-poll's timing with the real signals; the event log and tokens are mocks. */
class AgentPollServiceTest {
    private val tokens = mock(AgentTokenService::class.java)
    private val events = mock(AgentEventService::class.java)
    private val signals = AgentEventSignals()
    private val polls = AgentPollService(tokens, events, signals)
    private val agent = AgentAuthentication(UUID.randomUUID(), UUID.randomUUID(), "nas", setOf("sport"))
    private val types = AgentScope.SPORT.eventTypes
    private val empty = AgentEventsSlice(emptyList(), 3, hasMore = false, gap = false)
    private val one = AgentEventsSlice(listOf(row(4)), 4, hasMore = false, gap = false)

    @AfterEach
    fun stop() = signals.shutdown()

    @Test
    fun `waiting events answer at once and the heartbeat records the cursor and session`() {
        `when`(events.slice(agent.userId, 3, types)).thenReturn(one)
        val answer = poll(after = 3, wait = 30, itmo = "ok")
        assertEquals(one, answer.getNow(null))
        verify(tokens).recordPoll(agent.tokenId, 3, true)
    }

    @Test
    fun `an empty poll waits and returns as soon as an event is appended`() {
        `when`(events.slice(agent.userId, 3, types)).thenReturn(empty)
        val started = System.nanoTime()
        val answer = poll(after = 3, wait = 30)
        assertFalse(answer.isDone)
        assertEquals(1, signals.waitingCount())

        `when`(events.slice(agent.userId, 3, types)).thenReturn(one)
        signals.wake(agent.userId)

        assertEquals(one, answer.get(5, TimeUnit.SECONDS))
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10))
        assertEquals(0, signals.waitingCount())
    }

    @Test
    fun `an empty poll ends with an empty page after its wait`() {
        `when`(events.slice(agent.userId, 3, types)).thenReturn(empty)
        val started = System.nanoTime()
        val answer = poll(after = 3, wait = 1)
        assertEquals(empty, answer.get(5, TimeUnit.SECONDS))
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(900))
    }

    @Test
    fun `another user's append does not wake the poll and the token's next poll supersedes it`() {
        `when`(events.slice(agent.userId, 3, types)).thenReturn(empty)
        val first = poll(after = 3, wait = 30)
        signals.wake(UUID.randomUUID())
        Thread.sleep(100)
        assertFalse(first.isDone)

        val second = poll(after = 3, wait = 30)

        assertEquals(AgentEventsSlice(emptyList(), 3, hasMore = false, gap = false), first.get(5, TimeUnit.SECONDS))
        assertFalse(second.isDone)
        signals.wake(agent.userId)
        assertEquals(empty, second.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a gap answers at once and wait 0 never parks`() {
        val gap = AgentEventsSlice(emptyList(), 9, hasMore = false, gap = true)
        `when`(events.slice(agent.userId, 1, types)).thenReturn(gap)
        assertEquals(gap, poll(after = 1, wait = 30).getNow(null))
        `when`(events.slice(agent.userId, 3, types)).thenReturn(empty)
        assertEquals(empty, poll(after = 3, wait = 0).getNow(null))
        assertEquals(0, signals.waitingCount())
    }

    @Test
    fun `bad parameters are refused`() {
        assertFailsWith<InvalidRequestDataException> { poll(after = -1, wait = 0) }
        assertFailsWith<InvalidRequestDataException> { poll(after = 0, wait = 0, itmo = "yes") }
    }

    private fun poll(after: Long, wait: Int, itmo: String = "none"): CompletableFuture<AgentEventsSlice> {
        val answer = CompletableFuture<AgentEventsSlice>()
        polls.poll(agent, after, wait, itmo, { answer.complete(it) }, { answer.completeExceptionally(it) })
        return answer
    }

    private fun row(seq: Long) =
        AgentEventRow(seq, "sport-free:1:$seq", "SPORT_FREE_SIGN_LESSONS_PAYLOAD", "{}", Instant.EPOCH, Instant.EPOCH.plusSeconds(60))
}
