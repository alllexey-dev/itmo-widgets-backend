package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportLesson
import org.springframework.data.jpa.repository.JpaRepository

interface SportLessonRepository : JpaRepository<SportLesson, Long> {
}