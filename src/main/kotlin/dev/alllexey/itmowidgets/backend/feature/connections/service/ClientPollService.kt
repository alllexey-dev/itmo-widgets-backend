package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.ItmoStatus
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionRepository
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionAuthentication
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

/**
 * The long-poll of `GET /api/client/events`: records the agent's heartbeat and ITMO status, answers at once when
 * events wait, otherwise parks the poll until an append, the agent's next poll or `wait` seconds. No transaction or
 * connection is held while parked.
 */
@Service
class ClientPollService(
    private val connections: ConnectionRepository,
    private val events: ClientEventService,
    private val signals: ClientEventSignals,
    transactions: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val transaction = TransactionTemplate(transactions)

    fun poll(
        agent: ConnectionAuthentication,
        after: Long,
        waitSeconds: Int,
        itmo: String,
        answer: (ClientEventsSlice) -> Unit,
        fail: (Throwable) -> Unit,
    ) {
        if (after < 0) throw InvalidRequestDataException("after must not be negative")
        val status = ItmoStatus.fromWire(itmo) ?: throw InvalidRequestDataException("itmo must be ok, closed or none")
        val now = clock.instant()
        transaction.executeWithoutResult { connections.recordPoll(agent.connectionId, now, now.minus(POLL_STEP), status) }
        val types = agent.scopes.mapNotNull(ConnectionScope::fromWire).flatMap { it.eventTypes }.toSet()
        val first = events.slice(agent.userId, after, types)
        val wait = waitSeconds.coerceIn(0, MAX_WAIT_SECONDS)
        if (first.events.isNotEmpty() || first.gap || wait == 0) return answer(first)
        val waiter = signals.park(agent.connectionId, agent.userId, Duration.ofSeconds(wait.toLong())) { superseded ->
            if (superseded) {
                answer(ClientEventsSlice(emptyList(), after, hasMore = false, gap = false))
            } else {
                runCatching { events.slice(agent.userId, after, types) }.fold(answer, fail)
            }
        }
        // An append that committed between the first read and parking woke nobody.
        if (events.slice(agent.userId, after, types).events.isNotEmpty()) waiter.fire()
    }

    companion object {
        const val MAX_WAIT_SECONDS = 30
        val POLL_STEP: Duration = Duration.ofMinutes(1)
    }
}
