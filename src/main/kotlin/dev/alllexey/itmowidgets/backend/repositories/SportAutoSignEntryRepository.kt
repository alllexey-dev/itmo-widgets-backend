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
        Returns entries to show for user
     */
    @Query(
        """
        SELECT e FROM SportAutoSignEntity e
        WHERE e.user = :user
          AND e.prediction.start >= :cutoff
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
        Finds waiting entries using their frozen criteria. An unknown or non-specific
        building (ID 0) cannot establish a safe match.
     */
    @Query(
        """
        SELECT e FROM SportAutoSignEntity e
        WHERE (e.status = 'WAITING' OR e.status = 'NOTIFIED')
          AND NOT e.isCancelled
          AND e.prediction.sectionId = :sectionId
          AND e.prediction.teacherIsu = :teacherId
          AND e.prediction.buildingId = :buildingId
          AND e.prediction.buildingId <> 0
          AND e.prediction.roomId = :roomId
          AND e.prediction.sectionLevel = :sectionLevel
          AND e.prediction.lessonLevel = :lessonLevel
          AND e.prediction.typeId = :typeId
          AND e.prediction.timeSlotId = :timeSlotId
          AND e.prediction.start = :prototypeStart
          AND e.prediction.end = :prototypeEnd
        ORDER BY e.createdAt ASC, e.id ASC
    """
    )
    fun findMatchingWaitingEntries(
        @Param("sectionId") sectionId: Long,
        @Param("teacherId") teacherId: Long,
        @Param("buildingId") buildingId: Long,
        @Param("roomId") roomId: Long,
        @Param("sectionLevel") sectionLevel: Long,
        @Param("lessonLevel") lessonLevel: Long,
        @Param("typeId") typeId: Long,
        @Param("timeSlotId") timeSlotId: Long,
        @Param("prototypeStart") prototypeStart: OffsetDateTime,
        @Param("prototypeEnd") prototypeEnd: OffsetDateTime,
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

    fun findByPrototypeLessonIdAndStatusOrderByCreatedAt(
        lessonId: Long,
        status: QueueEntryStatus
    ): List<SportAutoSignEntity>

    @Query(
        """
        SELECT e FROM SportAutoSignEntity e
        WHERE (e.status = 'WAITING' OR e.status = 'NOTIFIED')
          AND NOT e.isCancelled
          AND e.prediction.end < :cutoff
    """
    )
    fun findExpiredEntries(@Param("cutoff") cutoff: OffsetDateTime): List<SportAutoSignEntity>

    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate(e.id, e.user.id)
        FROM SportAutoSignEntity e, SportLesson target
        WHERE target.id = :lessonId
          AND e.status = 'WAITING' AND NOT e.isCancelled AND e.realLesson IS NULL
          AND e.prediction.sectionId = target.section.id
          AND e.prediction.teacherIsu = target.teacher.isu
          AND e.prediction.buildingId = target.building.id
          AND e.prediction.buildingId <> 0
          AND e.prediction.roomId = target.roomId
          AND e.prediction.sectionLevel = target.sectionLevel
          AND e.prediction.lessonLevel = target.lessonLevel
          AND e.prediction.typeId = target.typeId
          AND e.prediction.timeSlotId = target.timeSlot.id
          AND e.prediction.start = target.start - 14 day
          AND e.prediction.end = target.end - 14 day
        ORDER BY e.createdAt ASC, e.id ASC
        """
    )
    fun findUnresolvedCandidates(lessonId: Long): List<SportQueueCandidate>

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
          AND e.prediction.end <= :cutoff
        ORDER BY e.createdAt ASC, e.id ASC
        """
    )
    fun findExpiredCandidates(cutoff: OffsetDateTime): List<SportQueueCandidate>
}
