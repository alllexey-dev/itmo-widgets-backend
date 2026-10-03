package dev.alllexey.itmowidgets.backend.feature.sport.persistence

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportTeacher
import org.springframework.data.jpa.repository.JpaRepository

interface SportTeacherRepository : JpaRepository<SportTeacher, Long>
