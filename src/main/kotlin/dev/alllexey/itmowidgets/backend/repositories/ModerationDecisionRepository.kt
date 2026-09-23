package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ModerationDecisionRepository : JpaRepository<ModerationDecisionEntity, UUID> {
    fun findAllByCaseIdOrderByCreatedAt(caseId: UUID): List<ModerationDecisionEntity>
}
