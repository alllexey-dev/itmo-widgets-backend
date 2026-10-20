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
import java.util.concurrent.ConcurrentHashMap

/**
 * Who may read FLOW links: ISU lists the viewer among the members of the flow. The uploaded schedule never proves
 * membership. A member list older than [IsuPotokCache.MEMBERS_TTL], or a flow ISU has not been asked about, is queued
 * for `IsuVerificationService` at most once per [RETRY]; a list up to [READ_AGE] old still answers meanwhile, a missing
 * one answers no. Only flow and ISU numbers are kept in memory.
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

    /** Callers hold the transaction. */
    fun isMember(userId: UUID, flowId: Long): Boolean {
        val now = clock.instant()
        val checkedAt = potoks.findById(flowId).orElse(null)?.membersCheckedAt
        if (checkedAt == null || checkedAt < now.minus(IsuPotokCache.MEMBERS_TTL)) request(flowId, now)
        if (checkedAt == null || checkedAt < now.minus(READ_AGE)) return false
        val isu = users.findIsuById(userId) ?: return false
        return members.existsById(IsuPotokMemberId(flowId, isu))
    }

    /** Up to [limit] queued flows; each leaves the queue. */
    fun take(limit: Int): List<Long> = wanted.take(limit).filter(wanted::remove)

    fun hasWanted(): Boolean = wanted.isNotEmpty()

    /** Without an ISU session the queue is dropped; the next read after [RETRY] asks again. */
    fun dropWanted() = wanted.clear()

    private fun request(flowId: Long, now: Instant) {
        val last = requested[flowId]
        if (last != null && last > now.minus(RETRY)) return
        if (requested.size >= MAX_REQUESTED) requested.values.removeIf { it <= now.minus(RETRY) }
        requested[flowId] = now
        wanted.add(flowId)
        isuCheck.ifAvailable { it.kick() }
    }

    companion object {
        val RETRY: Duration = Duration.ofMinutes(30)

        /** The nightly purge keeps a member list up to two days, so a refresh never leaves a busy flow without one. */
        val READ_AGE: Duration = Duration.ofDays(2)
        private const val MAX_REQUESTED = 1000
    }
}
