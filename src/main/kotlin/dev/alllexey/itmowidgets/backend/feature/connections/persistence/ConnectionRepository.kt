package dev.alllexey.itmowidgets.backend.feature.connections.persistence

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionEntity
import dev.alllexey.itmowidgets.backend.feature.connections.model.ItmoStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ConnectionRepository : JpaRepository<ConnectionEntity, UUID> {
    @Query("SELECT c FROM ConnectionEntity c WHERE c.tokenHash = :tokenHash AND c.revokedAt IS NULL")
    fun findActiveByHash(tokenHash: String): ConnectionEntity?

    /** Claimed live connections and revoked ones whose ITMO.ID session may still be open, newest first. */
    @Query(
        """
        SELECT c FROM ConnectionEntity c
        WHERE c.userId = :userId AND ((c.revokedAt IS NULL AND c.tokenHash IS NOT NULL) OR c.itmoSid IS NOT NULL AND c.revokedAt IS NOT NULL)
        ORDER BY c.createdAt DESC
    """,
    )
    fun findListed(userId: UUID): List<ConnectionEntity>

    @Query("SELECT COUNT(c) FROM ConnectionEntity c WHERE c.userId = :userId AND c.client = :client AND c.revokedAt IS NULL")
    fun countLive(userId: UUID, client: ClientType): Long

    /** A session of a connection, live or revoked, until it expires or the phone ends it. */
    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM ConnectionEntity c WHERE c.itmoSid = :sid AND c.itmoExpiresAt > :now",
    )
    fun existsSession(sid: String, now: Instant): Boolean

    /** Live agents of [userId] that polled after [since], for liveness. */
    @Query(
        """
        SELECT c FROM ConnectionEntity c
        WHERE c.userId = :userId AND c.client = dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType.AGENT
          AND c.revokedAt IS NULL AND c.lastPollAt > :since
    """,
    )
    fun findAgentsPolledSince(userId: UUID, since: Instant): List<ConnectionEntity>

    @Query("SELECT MAX(c.lastUsedAt) FROM ConnectionEntity c WHERE c.userId = :userId")
    fun findLastUsed(userId: UUID): Instant?

    fun countByClientAndCreatedAtGreaterThanEqual(client: ClientType, since: Instant): Long

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ConnectionEntity c SET c.lastUsedAt = :now WHERE c.id = :id AND (c.lastUsedAt IS NULL OR c.lastUsedAt < :before)")
    fun touch(id: UUID, now: Instant, before: Instant): Int

    /** A heartbeat: written at most once per step, at once when the ITMO session state changes. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE ConnectionEntity c SET c.lastPollAt = :now, c.itmoStatus = :status
        WHERE c.id = :id AND (c.lastPollAt IS NULL OR c.lastPollAt < :before OR c.itmoStatus <> :status)
    """,
    )
    fun recordPoll(id: UUID, now: Instant, before: Instant, status: ItmoStatus): Int

    /** Revoked rows drop their session id once it expired or the phone ended it. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE ConnectionEntity c SET c.itmoSid = NULL
        WHERE c.itmoSid IS NOT NULL AND c.revokedAt IS NOT NULL AND (c.itmoExpiresAt <= :now OR c.itmoEndedAt IS NOT NULL)
    """,
    )
    fun forgetEndedSessions(now: Instant): Int

    /** Approvals nobody claimed by [cutoff] and web connections unused since [webCutoff]. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE ConnectionEntity c SET c.revokedAt = :now
        WHERE c.revokedAt IS NULL AND (
            c.tokenHash IS NULL AND c.approvedAt < :cutoff
            OR c.client = dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType.WEB
               AND COALESCE(c.lastUsedAt, c.approvedAt) < :webCutoff
        )
    """,
    )
    fun revokeAbandoned(cutoff: Instant, webCutoff: Instant, now: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ConnectionEntity c WHERE c.revokedAt < :cutoff AND c.itmoSid IS NULL")
    fun deleteRevokedBefore(cutoff: Instant): Int
}
