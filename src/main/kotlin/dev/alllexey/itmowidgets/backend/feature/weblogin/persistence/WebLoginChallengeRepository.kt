package dev.alllexey.itmowidgets.backend.feature.weblogin.persistence

import dev.alllexey.itmowidgets.backend.feature.weblogin.model.WebLoginChallengeEntity
import dev.alllexey.itmowidgets.backend.feature.weblogin.model.WebLoginStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface WebLoginChallengeRepository : JpaRepository<WebLoginChallengeEntity, UUID> {
    /** The old web-login routes see only web sign-ins; keyed pairings never pass through them. */
    @Query(
        "SELECT c FROM WebLoginChallengeEntity c WHERE c.code = :code AND c.status = 'PENDING' AND c.expiresAt > :now AND c.kind = 'WEB_SESSION'",
    )
    fun findPendingByCode(code: String, now: Instant): WebLoginChallengeEntity?

    @Query("SELECT c FROM WebLoginChallengeEntity c WHERE c.code = :code AND c.status = 'PENDING' AND c.expiresAt > :now")
    fun findPendingPairingByCode(code: String, now: Instant): WebLoginChallengeEntity?

    /** Any pending row holds its code in the partial unique index, even after its expiry and before the scheduler runs. */
    fun existsByCodeAndStatus(code: String, status: WebLoginStatus): Boolean

    /** Unapproved challenges (still pending or already expired) this address created since [since]. */
    @Query(
        """
        SELECT COUNT(c) FROM WebLoginChallengeEntity c
        WHERE c.clientIp = :clientIp AND c.createdAt >= :since AND c.status IN ('PENDING', 'EXPIRED')
    """,
    )
    fun countByClientIpSince(clientIp: String, since: Instant): Long

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE WebLoginChallengeEntity c SET c.status = 'APPROVED', c.approvedBy = :userId, c.approvedAt = :now
        WHERE c.id = :id AND c.status = 'PENDING' AND c.expiresAt > :now AND c.kind = 'WEB_SESSION'
    """,
    )
    fun approve(id: UUID, userId: UUID, now: Instant): Int

    /** The first account that opens a code owns it; opening extends its life. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE WebLoginChallengeEntity c SET c.openedBy = :userId, c.openedAt = :now, c.expiresAt = :expiresAt
        WHERE c.id = :id AND c.status = 'PENDING' AND c.openedBy IS NULL AND c.expiresAt > :now
    """,
    )
    fun open(id: UUID, userId: UUID, now: Instant, expiresAt: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE WebLoginChallengeEntity c
        SET c.status = 'APPROVED', c.approvedBy = :userId, c.approvedAt = :now, c.sealedBundle = :bundle, c.scopes = :scopes
        WHERE c.id = :id AND c.status = 'PENDING' AND c.openedBy = :userId AND c.expiresAt > :now
    """,
    )
    fun approvePairing(id: UUID, userId: UUID, now: Instant, bundle: ByteArray?, scopes: String?): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE WebLoginChallengeEntity c SET c.status = 'REJECTED'
        WHERE c.id = :id AND c.status = 'PENDING' AND c.openedBy = :userId AND c.expiresAt > :now
    """,
    )
    fun reject(id: UUID, userId: UUID, now: Instant): Int

    /** A revoked agent token's session hand-offs end, their bundles with them. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE WebLoginChallengeEntity c SET c.status = 'EXPIRED', c.sealedBundle = NULL
        WHERE c.agentTokenId = :tokenId AND c.status IN ('PENDING', 'APPROVED')
    """,
    )
    fun expireForAgentToken(tokenId: UUID): Int

    /** Exactly one caller wins the APPROVED to CLAIMED transition; the bundle goes in the same statement. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WebLoginChallengeEntity c SET c.status = 'CLAIMED', c.sealedBundle = NULL WHERE c.id = :id AND c.status = 'APPROVED'")
    fun markClaimed(id: UUID): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WebLoginChallengeEntity c SET c.sealedBundle = NULL WHERE c.sealedBundle IS NOT NULL AND c.expiresAt < :cutoff")
    fun dropBundlesExpiredBefore(cutoff: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WebLoginChallengeEntity c SET c.status = 'EXPIRED' WHERE c.status = 'PENDING' AND c.expiresAt <= :now")
    fun expireBefore(now: Instant): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM WebLoginChallengeEntity c WHERE c.createdAt < :cutoff")
    fun deleteCreatedBefore(cutoff: Instant): Int
}
