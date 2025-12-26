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

interface SportAutoSignEntryRepository : JpaRepository<SportAutoSignEntity, Long> {

    fun findByUserAndPrototypeLessonAndStatus(
        user: User,
        prototypeLesson: SportLesson,
        status: QueueEntryStatus
    ): SportAutoSignEntity?

    @Query("""
        SELECT e FROM SportAutoSignEntity e 
        WHERE e.user = :user 
          AND e.prototypeLesson.start >= :cutoff
        ORDER BY e.createdAt DESC
    """)
    fun findRecentByUser(
        @Param("user") user: User,
        @Param("cutoff") cutoff: OffsetDateTime
    ): List<SportAutoSignEntity>

    @Query("""
        SELECT e FROM SportAutoSignEntity e 
        WHERE e.prototypeLesson.id IN :lessonIds 
          AND e.status = :status
        ORDER BY e.createdAt ASC
    """)
    fun findByPrototypeLessonIdInAndStatusOrderByCreatedAt(
        @Param("lessonIds") lessonIds: Collection<Long>,
        @Param("status") status: QueueEntryStatus
    ): List<SportAutoSignEntity>

    @Query("""
        SELECT COUNT(e) FROM SportAutoSignEntity e 
        WHERE e.user = :user 
        AND (
            e.status = 'WAITING' 
            OR (e.status IN ('NOTIFIED', 'SATISFIED') AND e.notifiedAt >= :cutoff)
        )
    """)
    fun countActiveEntriesInRollingWindow(
        @Param("user") user: User,
        @Param("cutoff") cutoff: Instant
    ): Int

    @Query("""
        SELECT e FROM SportAutoSignEntity e 
        WHERE e.user = :user 
        AND (
            e.status = 'WAITING' 
            OR (e.status IN ('NOTIFIED', 'SATISFIED') AND e.notifiedAt >= :cutoff)
        )
        ORDER BY COALESCE(e.notifiedAt, e.createdAt) ASC
    """)
    fun findOldestActiveEntry(
        @Param("user") user: User,
        @Param("cutoff") cutoff: Instant
    ): List<SportAutoSignEntity>

    @Query("""
        SELECT e FROM SportAutoSignEntity e 
        WHERE (e.status = 'WAITING' OR e.status = 'NOTIFIED')
        AND e.prototypeLesson.section.id = :sectionId
        AND e.prototypeLesson.teacher.isu = :teacherId
        AND e.prototypeLesson.sectionLevel = :level
        AND e.prototypeLesson.timeSlot.id = :timeSlotId
        AND e.prototypeLesson.start = :prototypeStart
        ORDER BY e.createdAt ASC
    """)
    fun findMatchingWaitingEntries(
        @Param("sectionId") sectionId: Long,
        @Param("teacherId") teacherId: Long,
        @Param("level") level: Long,
        @Param("timeSlotId") timeSlotId: Long,
        @Param("prototypeStart") prototypeStart: OffsetDateTime
    ): List<SportAutoSignEntity>

    @Query("""
        SELECT new dev.alllexey.itmowidgets.core.model.SportAutoSignQueue(
            e.prototypeLesson.id, 
            CAST(COUNT(e) as int)
        ) 
        FROM SportAutoSignEntity e 
        WHERE e.status = 'WAITING' 
        GROUP BY e.prototypeLesson.id
    """)
    fun findAllCurrentQueues(): List<SportAutoSignQueue>

    fun findByPrototypeLessonIdAndStatusOrderByCreatedAt(lessonId: Long, status: QueueEntryStatus): List<SportAutoSignEntity>

    @Query("SELECT e FROM SportAutoSignEntity e WHERE e.status = 'WAITING' AND e.prototypeLesson.end < :cutoff")
    fun findExpiredEntries(@Param("cutoff") cutoff: OffsetDateTime): List<SportAutoSignEntity>
}