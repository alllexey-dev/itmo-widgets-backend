package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.TeacherSummaryStateEntity
import org.springframework.data.jpa.repository.JpaRepository

interface TeacherSummaryStateRepository : JpaRepository<TeacherSummaryStateEntity, Short>
