package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.FreeSignEntryStatus
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
interface SportFreeSignEntryRepository : JpaRepository<SportFreeSignEntity, Long> {

    fun findByLessonIdAndStatusOrderByCreatedAt(lessonId: Long, status: FreeSignEntryStatus): List<SportFreeSignEntity>

    fun findByLessonIdInAndStatusOrderByCreatedAt(lessonIds: List<Long>, status: FreeSignEntryStatus): List<SportFreeSignEntity>

    fun findByUserAndStatusOrderByCreatedAt(user: User, status: FreeSignEntryStatus): List<SportFreeSignEntity>

    fun findByUserAndLessonAndStatus(user: User, lesson: SportLesson, status: FreeSignEntryStatus): SportFreeSignEntity?

    @Query("SELECT q FROM SportFreeSignEntity q WHERE q.status = 'WAITING' AND q.lesson.start < :currentTime")
    fun findExpiredEntries(currentTime: OffsetDateTime): List<SportFreeSignEntity>
}