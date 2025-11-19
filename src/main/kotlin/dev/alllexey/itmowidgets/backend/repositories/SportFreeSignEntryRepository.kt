package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import dev.alllexey.itmowidgets.core.model.SportFreeSignQueue
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

interface SportFreeSignEntryRepository : JpaRepository<SportFreeSignEntity, Long> {

    fun findByUserAndLessonAndStatus(
        user: User,
        lesson: SportLesson,
        status: QueueEntryStatus
    ): SportFreeSignEntity?

    fun findByLessonIdAndStatusOrderByCreatedAt(
        lessonId: Long,
        status: QueueEntryStatus
    ): List<SportFreeSignEntity>

    fun findByLessonIdInAndStatusOrderByCreatedAt(
        lessonIds: List<Long>,
        status: QueueEntryStatus
    ): List<SportFreeSignEntity>

    @Query("SELECT e FROM SportFreeSignEntity e WHERE e.status = 'WAITING' AND e.lesson.start < :currentTime")
    fun findExpiredEntries(
        currentTime: OffsetDateTime
    ): List<SportFreeSignEntity>

    @Query(
        """
        SELECT e FROM SportFreeSignEntity e 
        WHERE e.user = :user 
          AND e.lesson.end >= :cutoff
        ORDER BY e.createdAt DESC
    """
    )
    fun findRecentByUser(
        @Param("user") user: User,
        @Param("cutoff") cutoff: OffsetDateTime
    ): List<SportFreeSignEntity>

    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.core.model.SportFreeSignQueue(
            e.lesson.id, 
            CAST(COUNT(e) as int)
        ) 
        FROM SportFreeSignEntity e 
        WHERE e.status = 'WAITING' 
          AND e.lesson.end > :now 
        GROUP BY e.lesson.id
    """
    )
    fun findAllCurrentQueues(@Param("now") now: OffsetDateTime): List<SportFreeSignQueue>
}