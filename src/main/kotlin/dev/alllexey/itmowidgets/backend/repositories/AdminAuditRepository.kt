package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.AdminAuditEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface AdminAuditRepository : JpaRepository<AdminAuditEntity, UUID> {
    /** Newest first; the id breaks ties so pages never overlap. */
    @Query(
        value = "SELECT a FROM AdminAuditEntity a ORDER BY a.createdAt DESC, a.id DESC",
        countQuery = "SELECT COUNT(a) FROM AdminAuditEntity a",
    )
    fun findPage(pageable: Pageable): Page<AdminAuditEntity>
}
