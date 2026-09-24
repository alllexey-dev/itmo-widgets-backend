package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface UserRestrictionRepository : JpaRepository<UserRestrictionEntity, UUID> {
    @Query("SELECT r FROM UserRestrictionEntity r WHERE r.user.id = :userId AND r.startsAt <= :now AND r.revokedAt IS NULL AND (r.expiresAt IS NULL OR r.expiresAt > :now) ORDER BY r.startsAt DESC")
    fun findActive(userId: UUID, now: Instant): List<UserRestrictionEntity>

    fun findAllByUserIdOrderByStartsAtDesc(userId: UUID): List<UserRestrictionEntity>

    /** Newest first; a null [isu] matches every user, [activeOnly] keeps restrictions in force at [now]. */
    @Query(
        value = """
        SELECT new dev.alllexey.itmowidgets.backend.repositories.AdminRestrictionRow(
            r.id, u.id, r.capability, r.reason, r.startsAt, r.expiresAt, r.revokedAt, rb.isu, d.case.id)
        FROM UserRestrictionEntity r JOIN r.user u JOIN r.decision d LEFT JOIN r.revokedBy rb
        WHERE (:isu IS NULL OR u.isu = :isu)
          AND (:activeOnly = FALSE OR (r.revokedAt IS NULL AND r.startsAt <= :now AND (r.expiresAt IS NULL OR r.expiresAt > :now)))
        ORDER BY r.startsAt DESC, r.id DESC
        """,
        countQuery = """
        SELECT COUNT(r) FROM UserRestrictionEntity r JOIN r.user u
        WHERE (:isu IS NULL OR u.isu = :isu)
          AND (:activeOnly = FALSE OR (r.revokedAt IS NULL AND r.startsAt <= :now AND (r.expiresAt IS NULL OR r.expiresAt > :now)))
        """,
    )
    fun findAdminPage(isu: Int?, activeOnly: Boolean, now: Instant, pageable: Pageable): Page<AdminRestrictionRow>
}
