package dev.alllexey.itmowidgets.backend.feature.agent.service

import jakarta.annotation.PreDestroy
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Parked long-polls, one per token. A waiter completes once: woken by an append for its user, superseded by the same
 * token's next poll, or at its deadline. Completion work runs on a private pool, never on the appending thread. The pool is
 * not a bean: a `TaskScheduler` bean would take over every `@Scheduled` job.
 * shortcut: in-memory wake-up assumes one replica; a parked poll on another instance still answers at its deadline,
 * switch to PostgreSQL LISTEN/NOTIFY before scaling out.
 */
@Component
class AgentEventSignals {
    private val scheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 2
        setThreadNamePrefix("agent-poll-")
        setRemoveOnCancelPolicy(true)
        initialize()
    }

    /** [complete] gets true when superseded: the poll then answers an empty page. */
    inner class Waiter internal constructor(val tokenId: UUID, val userId: UUID, private val complete: (Boolean) -> Unit) {
        private val done = AtomicBoolean(false)

        fun fire(superseded: Boolean = false) {
            if (!done.compareAndSet(false, true)) return
            waiting.remove(tokenId, this)
            scheduler.execute { complete(superseded) }
        }
    }

    private val waiting = ConcurrentHashMap<UUID, Waiter>()

    /** Parks a poll until [wait] passes; a waiting poll of the same token is superseded. */
    fun park(tokenId: UUID, userId: UUID, wait: Duration, complete: (Boolean) -> Unit): Waiter {
        val waiter = Waiter(tokenId, userId, complete)
        waiting.put(tokenId, waiter)?.fire(superseded = true)
        scheduler.schedule({ waiter.fire() }, scheduler.clock.instant().plus(wait))
        return waiter
    }

    fun wake(userId: UUID) = waiting.values.filter { it.userId == userId }.forEach { it.fire() }

    fun waitingCount(): Int = waiting.size

    @PreDestroy
    fun shutdown() = scheduler.shutdown()
}
