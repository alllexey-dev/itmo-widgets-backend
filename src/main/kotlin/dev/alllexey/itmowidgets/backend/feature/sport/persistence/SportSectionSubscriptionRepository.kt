package dev.alllexey.itmowidgets.backend.feature.sport.persistence

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionCandidate
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionSubscription
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface SportSectionSubscriptionRepository : JpaRepository<SportSectionSubscription, Long> {

    @Query("SELECT s FROM SportSectionSubscription s WHERE s.user.id = :userId AND s.status = 'ACTIVE' ORDER BY s.createdAt, s.id")
    fun findActiveByUser(@Param("userId") userId: UUID): List<SportSectionSubscription>

    @Query("SELECT s FROM SportSectionSubscription s WHERE s.user.id = :userId AND s.slotKey = :slotKey AND s.status = 'ACTIVE'")
    fun findActive(@Param("userId") userId: UUID, @Param("slotKey") slotKey: String): SportSectionSubscription?

    @Query("SELECT COUNT(s) FROM SportSectionSubscription s WHERE s.slotKey = :slotKey AND s.status = 'ACTIVE'")
    fun countActive(@Param("slotKey") slotKey: String): Int

    /** Subscribers ahead in the slot's queue: subscription order, ties by id. */
    @Query(
        """
        SELECT COUNT(s) FROM SportSectionSubscription s
        WHERE s.slotKey = :slotKey AND s.status = 'ACTIVE'
          AND (s.createdAt < :createdAt OR (s.createdAt = :createdAt AND s.id < :id))
        """,
    )
    fun countActiveAhead(@Param("slotKey") slotKey: String, @Param("createdAt") createdAt: Instant, @Param("id") id: Long): Int

    /** Scheduler discovery in queue order: ids only, each subscription is reloaded under its owner's lock. */
    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionCandidate(s.id, s.user.id, s.slotKey)
        FROM SportSectionSubscription s WHERE s.status = 'ACTIVE' ORDER BY s.createdAt, s.id
        """,
    )
    fun findActiveCandidates(): List<SportSectionCandidate>
}
