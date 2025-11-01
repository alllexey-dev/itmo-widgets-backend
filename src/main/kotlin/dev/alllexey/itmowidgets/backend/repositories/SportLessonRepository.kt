package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportLesson
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface SportLessonRepository : JpaRepository<SportLesson, Long> {

    @Query("SELECT sl.id FROM SportLesson sl")
    fun findAllIds(): Set<Long>
}