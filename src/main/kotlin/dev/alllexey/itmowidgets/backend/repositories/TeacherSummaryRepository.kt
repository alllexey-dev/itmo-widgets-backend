package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.TeacherSummaryEntity
import org.springframework.data.jpa.repository.JpaRepository

interface TeacherSummaryRepository : JpaRepository<TeacherSummaryEntity, Int>
