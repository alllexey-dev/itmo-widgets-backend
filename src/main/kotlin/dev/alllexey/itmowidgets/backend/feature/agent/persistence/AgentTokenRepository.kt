package dev.alllexey.itmowidgets.backend.feature.agent.persistence

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentTokenEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface AgentTokenRepository : JpaRepository<AgentTokenEntity, UUID> {
    @Query("SELECT t FROM AgentTokenEntity t WHERE t.tokenHash = :tokenHash AND t.revokedAt IS NULL")
    fun findActiveByHash(tokenHash: String): AgentTokenEntity?

    @Query("SELECT t FROM AgentTokenEntity t WHERE t.userId = :userId AND t.revokedAt IS NULL ORDER BY t.createdAt")
    fun findActiveByUser(userId: UUID): List<AgentTokenEntity>

    @Query("SELECT COUNT(t) FROM AgentTokenEntity t WHERE t.userId = :userId AND t.revokedAt IS NULL")
    fun countActive(userId: UUID): Long

    fun countByUserIdAndCreatedAtGreaterThanEqual(userId: UUID, since: Instant): Long

    /** Active tokens of [userId] that polled after [since], with their scopes, for liveness. */
    @Query(
        """
        SELECT t FROM AgentTokenEntity t
        WHERE t.userId = :userId AND t.revokedAt IS NULL AND t.lastPollAt > :since
    """,
    )
    fun findPolledSince(userId: UUID, since: Instant): List<AgentTokenEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentTokenEntity t SET t.lastUsedAt = :now WHERE t.id = :id AND (t.lastUsedAt IS NULL OR t.lastUsedAt < :before)")
    fun touch(id: UUID, now: Instant, before: Instant): Int

    /** A heartbeat: written at most once per step, at once when the ITMO session state changes. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE AgentTokenEntity t SET t.lastPollAt = :now, t.itmoSessionOk = :itmoOk, t.ackedSeq = :after
        WHERE t.id = :id AND (t.lastPollAt IS NULL OR t.lastPollAt < :before OR t.itmoSessionOk <> :itmoOk)
    """,
    )
    fun recordPoll(id: UUID, now: Instant, before: Instant, itmoOk: Boolean, after: Long): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentTokenEntity t SET t.revokedAt = :now WHERE t.id = :id AND t.userId = :userId AND t.revokedAt IS NULL")
    fun revoke(id: UUID, userId: UUID, now: Instant): Int

    /** Tokens without a request since [cutoff] (or never used and created before it). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE AgentTokenEntity t SET t.revokedAt = :now
        WHERE t.revokedAt IS NULL AND COALESCE(t.lastUsedAt, t.createdAt) < :cutoff
    """,
    )
    fun revokeIdle(cutoff: Instant, now: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM AgentTokenEntity t WHERE t.revokedAt < :cutoff")
    fun deleteRevokedBefore(cutoff: Instant): Int
}
