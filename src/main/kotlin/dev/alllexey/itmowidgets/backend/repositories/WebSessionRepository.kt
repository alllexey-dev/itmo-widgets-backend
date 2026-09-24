package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface WebSessionRepository : JpaRepository<WebSessionEntity, UUID> {
    /** Not revoked and within the absolute lifetime; the idle limit is checked by the service. */
    @Query("SELECT s FROM WebSessionEntity s WHERE s.tokenHash = :tokenHash AND s.revokedAt IS NULL AND s.expiresAt > :now")
    fun findActiveByTokenHash(tokenHash: String, now: Instant): WebSessionEntity?

    @Query("""
        SELECT s FROM WebSessionEntity s
        WHERE s.userId = :userId AND s.revokedAt IS NULL AND s.expiresAt > :now
        ORDER BY s.lastSeenAt DESC
    """)
    fun findActiveByUser(userId: UUID, now: Instant): List<WebSessionEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WebSessionEntity s SET s.revokedAt = :now WHERE s.tokenHash = :tokenHash AND s.revokedAt IS NULL")
    fun revokeByTokenHash(tokenHash: String, now: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM WebSessionEntity s WHERE s.expiresAt < :cutoff")
    fun deleteExpiredBefore(cutoff: Instant): Int
}
