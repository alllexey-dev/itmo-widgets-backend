package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientEventRow
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.ItmoStatus
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionRepository
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.testing.TestClock
import dev.alllexey.itmowidgets.backend.testing.TestConnections
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The long-poll's timing with the real signals; the event log and the connections are mocks. */
class ClientPollServiceTest {
    private val connections = mock(ConnectionRepository::class.java)
    private val events = mock(ClientEventService::class.java)
    private val signals = ClientEventSignals()
    private val transactions = mock(PlatformTransactionManager::class.java)
    private val polls = ClientPollService(connections, events, signals, transactions, TestClock.CLOCK)
    private val agent = TestConnections.client(UUID.randomUUID(), ClientType.AGENT, setOf(ConnectionScope.SPORT))
    private val types = ConnectionScope.SPORT.eventTypes
    private val empty = ClientEventsSlice(emptyList(), 3, hasMore = false, gap = false)
    private val one = ClientEventsSlice(listOf(row(4)), 4, hasMore = false, gap = false)

    @BeforeEach
    fun transactions() {
        // Spring 7 declares the status non-null, so TransactionTemplate's callback needs a real one from the mock.
        `when`(transactions.getTransaction(nullable(TransactionDefinition::class.java))).thenReturn(SimpleTransactionStatus())
    }

    @AfterEach
    fun stop() = signals.shutdown()

    @Test
    fun `waiting events answer at once and the heartbeat records the ITMO session state`() {
        `when`(events.slice(agent.userId, 3, types)).thenReturn(one)
        val answer = poll(after = 3, wait = 30, itmo = "ok")
        assertEquals(one, answer.getNow(null))
        val now = TestClock.now()
        verify(connections).recordPoll(agent.connectionId, now, now.minus(ClientPollService.POLL_STEP), ItmoStatus.OK)
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
    fun `another user's append does not wake the poll and the agent's next poll supersedes it`() {
        `when`(events.slice(agent.userId, 3, types)).thenReturn(empty)
        val first = poll(after = 3, wait = 30)
        signals.wake(UUID.randomUUID())
        Thread.sleep(100)
        assertFalse(first.isDone)

        val second = poll(after = 3, wait = 30)

        assertEquals(ClientEventsSlice(emptyList(), 3, hasMore = false, gap = false), first.get(5, TimeUnit.SECONDS))
        assertFalse(second.isDone)
        signals.wake(agent.userId)
        assertEquals(empty, second.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a gap answers at once and wait 0 never parks`() {
        val gap = ClientEventsSlice(emptyList(), 9, hasMore = false, gap = true)
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

    private fun poll(after: Long, wait: Int, itmo: String = "none"): CompletableFuture<ClientEventsSlice> {
        val answer = CompletableFuture<ClientEventsSlice>()
        polls.poll(agent, after, wait, itmo, { answer.complete(it) }, { answer.completeExceptionally(it) })
        return answer
    }

    private fun row(seq: Long) =
        ClientEventRow(seq, "sport-free:1:$seq", "SPORT_FREE_SIGN_LESSONS_PAYLOAD", "{}", Instant.EPOCH, Instant.EPOCH.plusSeconds(60))
}
