package dev.alllexey.itmowidgets.backend.feature.admin.persistence

import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import java.time.Instant
import java.util.UUID

/**
 * The user list of the admin page: a read model over users, their roles and their moderation restrictions, which only
 * the admin feature composes.
 */
interface AdminUserSearchRepository : Repository<User, UUID> {
    /**
     * Newest first. [pattern] is a lowercase `%...%` LIKE pattern escaped with `!` and matches the name or a stored
     * group name; [isuPrefix] matches the ISU as text. A null pattern lists everybody. [role] keeps holders of the role;
     * [restricted] keeps users with (true) or without (false) a restriction in force at [now].
     */
    @Query(
        value = """
        SELECT new dev.alllexey.itmowidgets.backend.feature.admin.persistence.UserSummaryRow(u.id, u.isu, u.name, u.pictureUrl, u.createdAt)
        FROM User u
        WHERE $FILTERS
        ORDER BY u.createdAt DESC, u.id
        """,
        countQuery = "SELECT COUNT(u) FROM User u WHERE $FILTERS",
    )
    fun search(
        pattern: String?,
        isuPrefix: String,
        role: UserRole?,
        restricted: Boolean?,
        now: Instant,
        pageable: Pageable,
    ): Page<UserSummaryRow>
}

/** A restriction of `u` in force at `:now`. */
private const val IN_FORCE =
    "SELECT 1 FROM UserRestrictionEntity rs WHERE rs.user.id = u.id AND rs.revokedAt IS NULL AND rs.startsAt <= :now " +
        "AND (rs.expiresAt IS NULL OR rs.expiresAt > :now)"

private const val FILTERS = """
    (:pattern IS NULL
     OR CAST(u.isu AS String) LIKE :isuPrefix
     OR LOWER(u.name) LIKE :pattern ESCAPE '!'
     OR EXISTS (SELECT g.id FROM User x JOIN x.groups g WHERE x.id = u.id AND LOWER(g.name) LIKE :pattern ESCAPE '!'))
    AND (:role IS NULL OR EXISTS (SELECT 1 FROM UserRoleEntity ro WHERE ro.id.userId = u.id AND ro.id.role = :role))
    AND (:restricted IS NULL
         OR (:restricted = TRUE AND EXISTS ($IN_FORCE))
         OR (:restricted = FALSE AND NOT EXISTS ($IN_FORCE)))
"""
