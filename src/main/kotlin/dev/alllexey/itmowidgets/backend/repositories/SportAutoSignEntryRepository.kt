package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import dev.alllexey.itmowidgets.core.model.SportAutoSignQueue
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

interface SportAutoSignEntryRepository : JpaRepository<SportAutoSignEntity, Long> {

    @Query(
        """
        SELECT e FROM SportAutoSignEntity e
        WHERE e.user.id = :userId
          AND e.prototypeLesson.id = :prototypeLessonId
          AND NOT e.isCancelled
    """
    )
    fun findNotCancelledEntry(
        @Param("userId") userId: UUID,
        @Param("prototypeLessonId") prototypeLessonId: Long,
    ): SportAutoSignEntity?

    @Query(
        """
        SELECT e FROM SportAutoSignEntity e
        WHERE e.user.id = :userId
          AND e.realLesson.id = :realLessonId
          AND NOT e.isCancelled
    """
    )
    fun findAllNotCancelledEntriesByRealLesson(
        @Param("userId") userId: UUID,
        @Param("realLessonId") realLessonId: Long,
    ): List<SportAutoSignEntity>


    /*
        Returns entries to show for user: those whose predicted lesson has not started yet.
     */
    @Query(
        """
        SELECT e FROM SportAutoSignEntity e
        WHERE e.user = :user
          AND e.prediction.predictedStart >= :cutoff
          AND NOT e.isCancelled
        ORDER BY e.createdAt DESC, e.id DESC
    """
    )
    fun findRecentByUser(
        @Param("user") user: User,
        @Param("cutoff") cutoff: OffsetDateTime
    ): List<SportAutoSignEntity>

    @Query(
        """
        SELECT e FROM SportAutoSignEntity e
        WHERE e.prototypeLesson.id IN :lessonIds
          AND e.status IN :statuses
          AND NOT e.isCancelled
        ORDER BY e.createdAt ASC, e.id ASC
    """
    )
    fun findAllByPrototypeLessonsAndStatuses(
        @Param("lessonIds") lessonIds: Collection<Long>,
        @Param("statuses") statuses: List<QueueEntryStatus>
    ): List<SportAutoSignEntity>

    /*
        Entry is considered active either if:
        1) It's currently waiting for the notification
        2) It received at least one notification

        Active entries are used as measurements in monthly usage limits.
     */
    @Query(
        """
        SELECT COUNT(e) FROM SportAutoSignEntity e
        WHERE e.user = :user
        AND (
            (e.status = 'WAITING' AND NOT e.isCancelled)
            OR (e.firstNotifiedAt IS NOT NULL AND e.firstNotifiedAt >= :cutoff)
        )
    """
    )
    fun countActiveEntriesInRollingWindow(
        @Param("user") user: User,
        @Param("cutoff") cutoff: Instant
    ): Int

    /*
        Take first time of active entry is possible.
     */
    @Query(
        """
        SELECT e FROM SportAutoSignEntity e
        WHERE e.user = :user
          AND (
            (e.status = 'WAITING' AND NOT e.isCancelled)
            OR (e.firstNotifiedAt IS NOT NULL AND e.firstNotifiedAt >= :cutoff)
          )
        ORDER BY COALESCE(e.firstNotifiedAt, e.createdAt) ASC
    """
    )
    fun findOldestActiveEntry(
        @Param("user") user: User,
        @Param("cutoff") cutoff: Instant
    ): List<SportAutoSignEntity>

    /*
        Returns list of SportAutoSignQueue with total numbers of not cancelled active entries
     */
    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.core.model.SportAutoSignQueue(
            e.prototypeLesson.id,
            CAST(COUNT(e) as int),
            e.realLesson.id
        )
        FROM SportAutoSignEntity e
        WHERE (e.status = 'WAITING' OR e.status = 'NOTIFIED')
          AND NOT e.isCancelled
        GROUP BY e.prototypeLesson.id, e.realLesson.id
    """
    )
    fun findAllCurrentQueues(): List<SportAutoSignQueue>

    /*
        The forecast rule lives only in SportQueueRules; persistence compares its frozen key.
        A NULL key is unmatchable by SQL equality, which is exactly what an unusable venue means.
     */
    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate(e.id, e.user.id)
        FROM SportAutoSignEntity e
        WHERE e.status = 'WAITING' AND NOT e.isCancelled AND e.realLesson IS NULL
          AND e.prediction.matchKey = :matchKey
        ORDER BY e.createdAt ASC, e.id ASC
        """
    )
    fun findUnresolvedCandidates(@Param("matchKey") matchKey: String): List<SportQueueCandidate>

    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate(e.id, e.user.id)
        FROM SportAutoSignEntity e
        WHERE e.realLesson.id = :lessonId
          AND e.status IN ('WAITING', 'NOTIFIED') AND NOT e.isCancelled
        ORDER BY e.createdAt ASC, e.id ASC
        """
    )
    fun findBoundNotificationCandidates(lessonId: Long): List<SportQueueCandidate>

    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate(e.id, e.user.id)
        FROM SportAutoSignEntity e
        WHERE e.status IN ('WAITING', 'NOTIFIED') AND NOT e.isCancelled
          AND e.prediction.predictedEnd <= :cutoff
        ORDER BY e.createdAt ASC, e.id ASC
        """
    )
    fun findExpiredCandidates(@Param("cutoff") cutoff: OffsetDateTime): List<SportQueueCandidate>

    @Query("SELECT COUNT(e) FROM SportAutoSignEntity e WHERE e.status IN ('WAITING', 'NOTIFIED') AND NOT e.isCancelled")
    fun countActive(): Long
}
