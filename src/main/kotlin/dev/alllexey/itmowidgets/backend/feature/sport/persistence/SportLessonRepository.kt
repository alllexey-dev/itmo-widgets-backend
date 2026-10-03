package dev.alllexey.itmowidgets.backend.feature.sport.persistence

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportLesson
import org.springframework.data.jpa.repository.JpaRepository

interface SportLessonRepository : JpaRepository<SportLesson, Long>
