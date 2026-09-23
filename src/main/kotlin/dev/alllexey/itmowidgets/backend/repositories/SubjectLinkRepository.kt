package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.LinkCategory
import dev.alllexey.itmowidgets.backend.model.SubjectLinkEntity
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface SubjectLinkRepository : JpaRepository<SubjectLinkEntity, UUID> {
    /** Shared, not hidden links with approved content; the caller still checks each audience. */
    @Query("""
        SELECT l FROM SubjectLinkEntity l JOIN FETCH l.owner
        WHERE l.subjectId = :subjectId AND l.periodKey = :periodKey
          AND l.visibility <> dev.alllexey.itmowidgets.backend.model.LinkVisibility.PRIVATE
          AND l.hiddenAt IS NULL
          AND EXISTS (SELECT r.id FROM SubjectLinkRevisionEntity r WHERE r.link = l
              AND r.status = dev.alllexey.itmowidgets.backend.model.LinkRevisionStatus.APPROVED)
        """)
    fun findVisibleCandidates(subjectId: Long, periodKey: String): List<SubjectLinkEntity>

    /** Approved public links of earlier periods; period keys `YYYY-S` sort chronologically as text. */
    @Query("""
        SELECT l FROM SubjectLinkEntity l JOIN FETCH l.owner
        WHERE l.subjectId = :subjectId AND l.periodKey < :periodKey AND l.category IN :categories
          AND l.visibility = dev.alllexey.itmowidgets.backend.model.LinkVisibility.ALL
          AND l.hiddenAt IS NULL
          AND EXISTS (SELECT r.id FROM SubjectLinkRevisionEntity r WHERE r.link = l
              AND r.status = dev.alllexey.itmowidgets.backend.model.LinkRevisionStatus.APPROVED)
        ORDER BY l.score DESC, l.createdAt, l.id
        """)
    fun findPrevious(subjectId: Long, periodKey: String, categories: Collection<LinkCategory>, limit: Limit): List<SubjectLinkEntity>

    @Query("""
        SELECT l FROM SubjectLinkEntity l
        WHERE l.owner.id = :ownerId AND l.subjectId = :subjectId AND l.periodKey = :periodKey
        ORDER BY l.createdAt, l.id
        """)
    fun findOwned(ownerId: UUID, subjectId: Long, periodKey: String): List<SubjectLinkEntity>

    @Query(value = "SELECT id FROM subject_links WHERE id = :id FOR UPDATE", nativeQuery = true)
    fun lockById(id: UUID): UUID?
}
