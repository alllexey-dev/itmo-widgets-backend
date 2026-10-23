package dev.alllexey.itmowidgets.backend.feature.links.service

import dev.alllexey.itmowidgets.backend.feature.reviews.model.IsuPotokMemberId
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.IsuPotokMemberRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.IsuPotokRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.service.IsuPotokCache
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewVerificationKick
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Who may read FLOW links: ISU lists the viewer among the members of the flow. The uploaded schedule never proves
 * membership. A member list older than [IsuPotokCache.MEMBERS_TTL], or a flow ISU has not been asked about, is queued
 * for `IsuVerificationService` at most once per [RETRY]; a list up to [READ_AGE] old still answers meanwhile. Without one
 * a read waits up to [FIRST_READ_WAIT] for the queued fetch; after a failed or slow fetch it answers no until the next one.
 * Only flow and ISU numbers are kept in memory.
 */
@Component
class FlowReaders(
    private val users: UserRepository,
    private val potoks: IsuPotokRepository,
    private val members: IsuPotokMemberRepository,
    private val isuCheck: ObjectProvider<ReviewVerificationKick>,
    private val clock: Clock,
) {
    private val wanted = ConcurrentHashMap.newKeySet<Long>()
    private val requested = ConcurrentHashMap<Long, Instant>()
    private val fetches = ConcurrentHashMap<Long, CompletableFuture<Set<Int>?>>()

    /** Callers hold the transaction. */
    fun isMember(userId: UUID, flowId: Long): Boolean {
        val now = clock.instant()
        val checkedAt = potoks.findById(flowId).orElse(null)?.membersCheckedAt
        val fetch = if (checkedAt == null || checkedAt < now.minus(IsuPotokCache.MEMBERS_TTL)) request(flowId, now) else null
        val isu = users.findIsuById(userId) ?: return false
        if (checkedAt == null || checkedAt < now.minus(READ_AGE)) {
            val listed = fetch?.await() ?: return false
            return isu in listed
        }
        return members.existsById(IsuPotokMemberId(flowId, isu))
    }

    /**
     * The run fetched [flowId]: [members] is its list, null when the request failed. The answer stays until the next
     * request of the flow, so a read that missed the new list in the database still gets it.
     */
    fun fetched(flowId: Long, members: Set<Int>?) {
        fetches[flowId]?.complete(members)
    }

    /** Up to [limit] queued flows; each leaves the queue. */
    fun take(limit: Int): List<Long> = wanted.take(limit).filter(wanted::remove)

    fun hasWanted(): Boolean = wanted.isNotEmpty()

    /** Without an ISU session the queue is dropped; the next read after [RETRY] asks again. */
    fun dropWanted() {
        wanted.clear()
        fetches.values.forEach { it.complete(null) }
    }

    /** The latest fetch of [flowId] to wait for, null when there is none or no run can answer it. */
    private fun request(flowId: Long, now: Instant): CompletableFuture<Set<Int>?>? {
        val last = requested[flowId]
        if (last != null && last > now.minus(RETRY)) return fetches[flowId]
        if (requested.size >= MAX_REQUESTED) {
            requested.values.removeIf { it <= now.minus(RETRY) }
            fetches.keys.retainAll(requested.keys)
        }
        requested[flowId] = now
        val kick = isuCheck.ifAvailable
        val fetch = kick?.let { CompletableFuture<Set<Int>?>().also { fetches.put(flowId, it)?.complete(null) } }
        wanted.add(flowId)
        kick?.kick()
        return fetch
    }

    private fun CompletableFuture<Set<Int>?>.await(): Set<Int>? = try {
        get(FIRST_READ_WAIT.toMillis(), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    }

    companion object {
        val RETRY: Duration = Duration.ofMinutes(30)

        /** A login and one member list take about two seconds. */
        val FIRST_READ_WAIT: Duration = Duration.ofSeconds(5)

        /** The nightly purge keeps a member list up to two days, so a refresh never leaves a busy flow without one. */
        val READ_AGE: Duration = Duration.ofDays(2)
        private const val MAX_REQUESTED = 1000
    }
}
