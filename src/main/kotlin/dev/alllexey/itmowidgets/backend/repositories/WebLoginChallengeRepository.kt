package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface WebLoginChallengeRepository : JpaRepository<WebLoginChallengeEntity, UUID> {
    @Query("SELECT c FROM WebLoginChallengeEntity c WHERE c.code = :code AND c.status = 'PENDING' AND c.expiresAt > :now")
    fun findPendingByCode(code: String, now: Instant): WebLoginChallengeEntity?

    /** Any pending row holds its code in the partial unique index, even after its expiry and before the scheduler runs. */
    fun existsByCodeAndStatus(code: String, status: WebLoginStatus): Boolean

    /** Unapproved challenges (still pending or already expired) this address created since [since]. */
    @Query("""
        SELECT COUNT(c) FROM WebLoginChallengeEntity c
        WHERE c.clientIp = :clientIp AND c.createdAt >= :since AND c.status IN ('PENDING', 'EXPIRED')
    """)
    fun countByClientIpSince(clientIp: String, since: Instant): Long

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE WebLoginChallengeEntity c SET c.status = 'APPROVED', c.approvedBy = :userId, c.approvedAt = :now
        WHERE c.id = :id AND c.status = 'PENDING' AND c.expiresAt > :now
    """)
    fun approve(id: UUID, userId: UUID, now: Instant): Int

    /** Exactly one caller wins the APPROVED to CLAIMED transition. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WebLoginChallengeEntity c SET c.status = 'CLAIMED' WHERE c.id = :id AND c.status = 'APPROVED'")
    fun markClaimed(id: UUID): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WebLoginChallengeEntity c SET c.status = 'EXPIRED' WHERE c.status = 'PENDING' AND c.expiresAt <= :now")
    fun expireBefore(now: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM WebLoginChallengeEntity c WHERE c.createdAt < :cutoff")
    fun deleteCreatedBefore(cutoff: Instant): Int
}
