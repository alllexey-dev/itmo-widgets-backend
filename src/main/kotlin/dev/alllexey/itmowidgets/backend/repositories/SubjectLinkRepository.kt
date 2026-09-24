package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.LinkCategory
import dev.alllexey.itmowidgets.backend.model.SubjectLinkEntity
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID
import java.time.Instant

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

    @Query("SELECT l FROM SubjectLinkEntity l WHERE l.owner.id = :ownerId ORDER BY l.createdAt, l.id")
    fun findAllByOwner(ownerId: UUID): List<SubjectLinkEntity>

    @Query(value = "SELECT id FROM subject_links WHERE id = :id FOR UPDATE", nativeQuery = true)
    fun lockById(id: UUID): UUID?

    fun countByOwnerId(ownerId: UUID): Long

    /** Links by creation day in [zone] since [from]. */
    @Query(
        nativeQuery = true,
        value = """
        SELECT to_char(CAST(created_at AT TIME ZONE :zone AS date), 'YYYY-MM-DD') AS label, COUNT(*) AS total
        FROM subject_links WHERE created_at >= :from GROUP BY 1
        """,
    )
    fun countCreatedPerDay(from: Instant, zone: String): List<LabelCount>

    /** Links by the owner-side status of `SubjectLinkViews.ownerStatus`, computed from the newest revision. */
    @Query(
        nativeQuery = true,
        value = """
        SELECT CASE
                   WHEN l.hidden_at IS NOT NULL THEN 'HIDDEN'
                   WHEN l.visibility = 'PRIVATE' OR r.status IS NULL THEN 'PRIVATE'
                   WHEN r.status = 'PENDING' THEN 'PENDING'
                   WHEN r.status = 'REJECTED' THEN 'REJECTED'
                   ELSE 'PUBLISHED'
               END AS label,
               COUNT(*) AS total
        FROM subject_links l
        LEFT JOIN LATERAL (
            SELECT x.status FROM subject_link_revisions x WHERE x.link_id = l.id ORDER BY x.number DESC LIMIT 1
        ) r ON TRUE
        GROUP BY 1
        """,
    )
    fun countByOwnerStatus(): List<LabelCount>
}
