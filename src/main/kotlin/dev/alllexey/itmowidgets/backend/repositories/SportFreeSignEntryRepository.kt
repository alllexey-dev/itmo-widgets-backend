package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import dev.alllexey.itmowidgets.core.model.SportFreeSignQueue
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface SportFreeSignEntryRepository : JpaRepository<SportFreeSignEntity, Long> {

    @Query("""
        SELECT e FROM SportFreeSignEntity e
        WHERE e.user.id = :userId
          AND e.lesson.id = :lessonId
          AND NOT e.isCancelled
    """)
    fun findNotCancelledEntry(
        @Param("userId") userId: UUID,
        @Param("lessonId") lessonId: Long,
    ): SportFreeSignEntity?

    /*
        Returns entries to show for user
    */
    @Query(
        """
        SELECT e FROM SportFreeSignEntity e
        WHERE e.user = :user
          AND e.lesson.end >= :cutoff
          AND NOT e.isCancelled
        ORDER BY e.createdAt DESC, e.id DESC
    """
    )
    fun findRecentByUser(
        @Param("user") user: User,
        @Param("cutoff") cutoff: OffsetDateTime
    ): List<SportFreeSignEntity>

    @Query("""
        SELECT e FROM SportFreeSignEntity e
        WHERE e.lesson.id IN :lessonIds
          AND e.status IN :statuses
          AND NOT e.isCancelled
        ORDER BY e.createdAt ASC, e.id ASC
    """)
    fun findAllByLessonsAndStatuses(
        @Param("lessonIds") lessonIds: Collection<Long>,
        @Param("statuses") statuses: List<QueueEntryStatus>
    ): List<SportFreeSignEntity>

    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.core.model.SportFreeSignQueue(
            e.lesson.id,
            CAST(COUNT(e) as int)
        )
        FROM SportFreeSignEntity e
        WHERE (e.status = 'WAITING' OR e.status = 'NOTIFIED')
          AND NOT e.isCancelled
        GROUP BY e.lesson.id
    """
    )
    fun findAllCurrentQueues(@Param("now") now: OffsetDateTime): List<SportFreeSignQueue>

    @Query("""
        SELECT e FROM SportFreeSignEntity e
        WHERE (e.status = 'WAITING' OR e.status = 'NOTIFIED')
          AND NOT e.isCancelled
          AND e.lesson.start < :currentTime
    """)
    fun findExpiredEntries(
        currentTime: OffsetDateTime
    ): List<SportFreeSignEntity>

    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate(e.id, e.user.id)
        FROM SportFreeSignEntity e
        WHERE e.lesson.id = :lessonId
          AND e.status IN ('WAITING', 'NOTIFIED') AND NOT e.isCancelled
        ORDER BY e.createdAt ASC, e.id ASC
        """
    )
    fun findNotificationCandidates(lessonId: Long): List<SportQueueCandidate>

    /*
        Deliberately a superset, not the rule: a free deadline tracks a lesson that catalog refresh
        can still move, so it cannot be frozen the way a forecast is. SportQueueRules.freeExpiryHorizon
        bounds every expired entry of either kind, and expireFreeEntry applies the rule itself after
        taking the owner lock. Selecting a few live entries costs one released lock each.
     */
    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate(e.id, e.user.id)
        FROM SportFreeSignEntity e
        WHERE e.status IN ('WAITING', 'NOTIFIED') AND NOT e.isCancelled
          AND e.lesson.start <= :horizon
        ORDER BY e.createdAt ASC, e.id ASC
        """
    )
    fun findExpiredCandidates(@Param("horizon") horizon: OffsetDateTime): List<SportQueueCandidate>
}
