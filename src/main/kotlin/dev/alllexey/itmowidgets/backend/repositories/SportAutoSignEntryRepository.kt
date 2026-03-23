package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.model.SportLesson
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
    fun findNotCancelledEntryByRealLesson(
        @Param("userId") userId: UUID,
        @Param("realLessonId") realLessonId: Long,
    ): SportAutoSignEntity?


    /*
        Returns entries to show for user
     */
    @Query(
        """
        SELECT e FROM SportAutoSignEntity e 
        WHERE e.user = :user 
          AND e.prototypeLesson.start >= :cutoff
          AND NOT e.isCancelled
        ORDER BY e.createdAt DESC
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
        ORDER BY e.createdAt ASC
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
        Finds waiting entries for given lesson data
     */
    @Query(
        """
        SELECT e FROM SportAutoSignEntity e 
        WHERE (e.status = 'WAITING' OR e.status = 'NOTIFIED')
          AND NOT e.isCancelled
          AND e.prototypeLesson.section.id = :sectionId
          AND e.prototypeLesson.teacher.isu = :teacherId
          AND e.prototypeLesson.sectionLevel = :sectionLevel
          AND e.prototypeLesson.lessonLevel = :lessonLevel
          AND e.prototypeLesson.timeSlot.id = :timeSlotId
          AND e.prototypeLesson.start = :prototypeStart
        ORDER BY e.createdAt ASC
    """
    )
    fun findMatchingWaitingEntries(
        @Param("sectionId") sectionId: Long,
        @Param("teacherId") teacherId: Long,
        @Param("sectionLevel") sectionLevel: Long,
        @Param("lessonLevel") lessonLevel: Long,
        @Param("timeSlotId") timeSlotId: Long,
        @Param("prototypeStart") prototypeStart: OffsetDateTime
    ): List<SportAutoSignEntity>

    /*
        Returns list of SportAutoSignQueue with total numbers of not cancelled active entries
     */
    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.core.model.SportAutoSignQueue(
            e.prototypeLesson.id, 
            CAST(COUNT(e) as int)
        ) 
        FROM SportAutoSignEntity e
        WHERE (e.status = 'WAITING' OR e.status = 'NOTIFIED') 
          AND NOT e.isCancelled
        GROUP BY e.prototypeLesson.id
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
          AND e.prototypeLesson.end < :cutoff
    """
    )
    fun findExpiredEntries(@Param("cutoff") cutoff: OffsetDateTime): List<SportAutoSignEntity>
}