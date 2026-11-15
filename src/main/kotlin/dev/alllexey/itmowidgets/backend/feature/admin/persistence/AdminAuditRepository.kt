package dev.alllexey.itmowidgets.backend.feature.admin.persistence

import dev.alllexey.itmowidgets.backend.feature.admin.model.AdminAuditEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface AdminAuditRepository : JpaRepository<AdminAuditEntity, UUID> {
    /**
     * Newest first; the id breaks ties so pages never overlap. Every filter is optional and they combine by AND:
     * [actorIsu] the acting admin, [targetType] the part of the target before its colon (the whole target without
     * one), [from] inclusive and [to] exclusive. The bounds are cast because PostgreSQL cannot type a null timestamp.
     */
    @Query(
        value = """
        SELECT a FROM AdminAuditEntity a
        WHERE $FILTERS
        ORDER BY a.createdAt DESC, a.id DESC
        """,
        countQuery = "SELECT COUNT(a) FROM AdminAuditEntity a WHERE $FILTERS",
    )
    fun findPage(
        pageable: Pageable,
        actorIsu: Int? = null,
        action: String? = null,
        targetType: String? = null,
        target: String? = null,
        from: Instant? = null,
        to: Instant? = null,
    ): Page<AdminAuditEntity>
}

private const val FILTERS = """
    (:actorIsu IS NULL OR EXISTS (SELECT 1 FROM User u WHERE u.id = a.actorId AND u.isu = :actorIsu))
    AND (:action IS NULL OR a.action = :action)
    AND (:targetType IS NULL
         OR CASE WHEN LOCATE(':', a.target) = 0 THEN a.target ELSE SUBSTRING(a.target, 1, LOCATE(':', a.target) - 1) END = :targetType)
    AND (:target IS NULL OR a.target = :target)
    AND (CAST(:from AS Instant) IS NULL OR a.createdAt >= :from)
    AND (CAST(:to AS Instant) IS NULL OR a.createdAt < :to)
"""
