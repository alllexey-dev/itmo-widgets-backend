package dev.alllexey.itmowidgets.backend.feature.agent.service

import com.fasterxml.jackson.annotation.JsonInclude
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentEventRow
import dev.alllexey.itmowidgets.backend.feature.agent.persistence.AgentEventStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One page of a user's events after a cursor (docs/contracts/agent.md#events). */
data class AgentEventsSlice(val events: List<AgentEventRow>, val nextCursor: Long, val hasMore: Boolean, val gap: Boolean)

/**
 * The per-user event log agents long-poll. Appends serialize on the user's `agent_users` row, so `seq` is gap-free and
 * safe with several instances; waiting polls are woken after commit.
 */
@Service
class AgentEventService(
    private val store: AgentEventStore,
    private val signals: AgentEventSignals,
    jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    // The push envelope's mapper: nulls omitted, so the payload equals the FCM one.
    private val payloadMapper: JsonMapper = jsonMapper.rebuild()
        .changeDefaultPropertyInclusion { JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL) }
        .build()

    /** Appends once per [dedupKey]; returns false for a repeat. [expiresAt] is clamped to [RETENTION] from now. */
    @Transactional
    fun append(userId: UUID, type: String, dedupKey: String, payload: Any, expiresAt: Instant): Boolean {
        val last = store.lockSequence(userId)
        if (store.exists(userId, dedupKey)) return false
        val now = clock.instant()
        val expires = minOf(expiresAt, now.plus(RETENTION))
        store.insert(userId, last + 1, dedupKey.take(MAX_DEDUP_LENGTH), type, payloadMapper.writeValueAsString(payload), now, expires)
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = signals.wake(userId)
            },
        )
        return true
    }

    /** Reads the sequence before the events, so every event up to it is committed and visible. */
    @Transactional(readOnly = true)
    fun slice(userId: UUID, after: Long, types: Set<String>): AgentEventsSlice {
        val last = store.lastSeq(userId)
        if (after > last) return AgentEventsSlice(emptyList(), last, hasMore = false, gap = true)
        val oldest = store.oldestSeq(userId)
        val gap = after < last && (oldest == null || oldest > after + 1)
        val rows = store.events(userId, after, last, types, PAGE_SIZE + 1)
        val page = rows.take(PAGE_SIZE)
        val hasMore = rows.size > PAGE_SIZE
        return AgentEventsSlice(page, if (hasMore) page.last().seq else last, hasMore, gap)
    }

    companion object {
        const val PAGE_SIZE = 100
        const val KEEP_PER_USER = 1000
        private const val MAX_DEDUP_LENGTH = 100
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
