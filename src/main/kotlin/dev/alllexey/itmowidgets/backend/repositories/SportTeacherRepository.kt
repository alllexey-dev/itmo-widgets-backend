package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportTeacher
import org.springframework.data.jpa.repository.JpaRepository

interface SportTeacherRepository : JpaRepository<SportTeacher, Long> {
}