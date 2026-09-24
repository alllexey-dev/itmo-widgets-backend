package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.LinkRevisionStatus
import dev.alllexey.itmowidgets.backend.model.SubjectLinkRevisionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface SubjectLinkRevisionRepository : JpaRepository<SubjectLinkRevisionEntity, UUID> {
    @Query("""
        SELECT r FROM SubjectLinkRevisionEntity r
        WHERE r.link.id = :linkId
          AND r.number = (SELECT MAX(x.number) FROM SubjectLinkRevisionEntity x WHERE x.link.id = :linkId)
        """)
    fun findLatest(linkId: UUID): SubjectLinkRevisionEntity?

    @Query("""
        SELECT r FROM SubjectLinkRevisionEntity r
        WHERE r.link.id = :linkId
          AND r.number = (SELECT MAX(x.number) FROM SubjectLinkRevisionEntity x WHERE x.link.id = :linkId
              AND x.status = dev.alllexey.itmowidgets.backend.model.LinkRevisionStatus.APPROVED)
        """)
    fun findLatestApproved(linkId: UUID): SubjectLinkRevisionEntity?

    @Query("""
        SELECT r FROM SubjectLinkRevisionEntity r
        WHERE r.link.id = :linkId AND r.status = dev.alllexey.itmowidgets.backend.model.LinkRevisionStatus.PENDING
        """)
    fun findPending(linkId: UUID): SubjectLinkRevisionEntity?

    @Query("SELECT COUNT(r) FROM SubjectLinkRevisionEntity r WHERE r.link.owner.id = :ownerId AND r.submittedAt >= :since")
    fun countByOwnerSince(ownerId: UUID, since: Instant): Long

    @Query("SELECT r FROM SubjectLinkRevisionEntity r WHERE r.link.id = :linkId ORDER BY r.number")
    fun findAllByLink(linkId: UUID): List<SubjectLinkRevisionEntity>

    /** The newest revision of each given link. */
    @Query("""
        SELECT r FROM SubjectLinkRevisionEntity r
        WHERE r.link.id IN :linkIds
          AND r.number = (SELECT MAX(x.number) FROM SubjectLinkRevisionEntity x WHERE x.link = r.link)
        """)
    fun findLatestIn(linkIds: Collection<UUID>): List<SubjectLinkRevisionEntity>

    /** The newest approved revision of each given link: the content other viewers see. */
    @Query("""
        SELECT r FROM SubjectLinkRevisionEntity r
        WHERE r.link.id IN :linkIds
          AND r.number = (SELECT MAX(x.number) FROM SubjectLinkRevisionEntity x WHERE x.link = r.link
              AND x.status = dev.alllexey.itmowidgets.backend.model.LinkRevisionStatus.APPROVED)
        """)
    fun findLatestApprovedIn(linkIds: Collection<UUID>): List<SubjectLinkRevisionEntity>

    @Query("""
        SELECT r FROM SubjectLinkRevisionEntity r
        WHERE r.link.owner.id = :ownerId AND r.status = dev.alllexey.itmowidgets.backend.model.LinkRevisionStatus.PENDING
        """)
    fun findPendingByOwner(ownerId: UUID): List<SubjectLinkRevisionEntity>

    @Query("SELECT COUNT(r) FROM SubjectLinkRevisionEntity r WHERE r.link.owner.id = :ownerId AND r.status = :status")
    fun countByOwnerAndStatus(ownerId: UUID, status: LinkRevisionStatus): Long

    /** Revisions with their links in one query; owners stay unloaded. */
    @Query("SELECT r FROM SubjectLinkRevisionEntity r JOIN FETCH r.link WHERE r.id IN :ids")
    fun findAllWithLink(ids: Collection<UUID>): List<SubjectLinkRevisionEntity>
}
