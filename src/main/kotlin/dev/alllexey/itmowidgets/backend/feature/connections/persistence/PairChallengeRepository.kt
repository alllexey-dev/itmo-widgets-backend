package dev.alllexey.itmowidgets.backend.feature.connections.persistence

import dev.alllexey.itmowidgets.backend.feature.connections.model.PairChallengeEntity
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface PairChallengeRepository : JpaRepository<PairChallengeEntity, UUID> {
    @Query("SELECT c FROM PairChallengeEntity c WHERE c.code = :code AND c.status = 'PENDING' AND c.expiresAt > :now")
    fun findPendingByCode(code: String, now: Instant): PairChallengeEntity?

    /** Any pending row holds its code in the partial unique index, even after its expiry and before the scheduler runs. */
    fun existsByCodeAndStatus(code: String, status: PairStatus): Boolean

    /** Unclaimed challenges this address created since [since]. */
    @Query(
        """
        SELECT COUNT(c) FROM PairChallengeEntity c
        WHERE c.clientIp = :clientIp AND c.createdAt >= :since AND c.status IN ('PENDING', 'EXPIRED')
    """,
    )
    fun countByClientIpSince(clientIp: String, since: Instant): Long

    /** The first account that opens a code owns it; opening extends its life. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE PairChallengeEntity c SET c.openedBy = :userId, c.openedAt = :now, c.expiresAt = :expiresAt
        WHERE c.id = :id AND c.status = 'PENDING' AND c.openedBy IS NULL AND c.expiresAt > :now
    """,
    )
    fun open(id: UUID, userId: UUID, now: Instant, expiresAt: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE PairChallengeEntity c
        SET c.status = 'APPROVED', c.approvedAt = :now, c.sealedBundle = :bundle, c.connectionId = :connectionId
        WHERE c.id = :id AND c.status = 'PENDING' AND c.openedBy = :userId AND c.expiresAt > :now
    """,
    )
    fun approve(id: UUID, userId: UUID, now: Instant, bundle: ByteArray?, connectionId: UUID): Int

    /** Exactly one caller wins the APPROVED to CLAIMED transition; the bundle goes in the same statement. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PairChallengeEntity c SET c.status = 'CLAIMED', c.sealedBundle = NULL WHERE c.id = :id AND c.status = 'APPROVED'")
    fun markClaimed(id: UUID): Int

    /** A revoked connection's renewals end, their bundles with them. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE PairChallengeEntity c SET c.status = 'EXPIRED', c.sealedBundle = NULL
        WHERE c.connectionId = :connectionId AND c.status IN ('PENDING', 'APPROVED')
    """,
    )
    fun expireForConnection(connectionId: UUID): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PairChallengeEntity c SET c.status = 'EXPIRED' WHERE c.status = 'PENDING' AND c.expiresAt <= :now")
    fun expireBefore(now: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PairChallengeEntity c SET c.sealedBundle = NULL WHERE c.sealedBundle IS NOT NULL AND c.expiresAt < :cutoff")
    fun dropBundlesExpiredBefore(cutoff: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PairChallengeEntity c WHERE c.createdAt < :cutoff")
    fun deleteCreatedBefore(cutoff: Instant): Int
}
