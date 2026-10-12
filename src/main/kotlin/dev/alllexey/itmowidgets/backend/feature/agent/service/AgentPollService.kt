package dev.alllexey.itmowidgets.backend.feature.agent.service

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.security.AgentAuthentication
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * The long-poll of `GET /api/agent/events`: answers at once when events wait, otherwise parks the poll until an append,
 * the token's next poll or `wait` seconds. No transaction or connection is held while parked.
 */
@Service
class AgentPollService(
    private val tokens: AgentTokenService,
    private val events: AgentEventService,
    private val signals: AgentEventSignals,
) {
    fun poll(
        agent: AgentAuthentication,
        after: Long,
        waitSeconds: Int,
        itmo: String,
        answer: (AgentEventsSlice) -> Unit,
        fail: (Throwable) -> Unit,
    ) {
        if (after < 0) throw InvalidRequestDataException("after must not be negative")
        val itmoOk = when (itmo) {
            "ok" -> true
            "none" -> false
            else -> throw InvalidRequestDataException("itmo must be ok or none")
        }
        tokens.recordPoll(agent.tokenId, after, itmoOk)
        val types = agent.scopes.mapNotNull(AgentScope::fromWire).flatMap { it.eventTypes }.toSet()
        val first = events.slice(agent.userId, after, types)
        val wait = waitSeconds.coerceIn(0, MAX_WAIT_SECONDS)
        if (first.events.isNotEmpty() || first.gap || wait == 0) return answer(first)
        val waiter = signals.park(agent.tokenId, agent.userId, Duration.ofSeconds(wait.toLong())) { superseded ->
            if (superseded) {
                answer(AgentEventsSlice(emptyList(), after, hasMore = false, gap = false))
            } else {
                runCatching { events.slice(agent.userId, after, types) }.fold(answer, fail)
            }
        }
        // An append that committed between the first read and parking woke nobody.
        if (events.slice(agent.userId, after, types).events.isNotEmpty()) waiter.fire()
    }

    companion object {
        const val MAX_WAIT_SECONDS = 30
    }
}
