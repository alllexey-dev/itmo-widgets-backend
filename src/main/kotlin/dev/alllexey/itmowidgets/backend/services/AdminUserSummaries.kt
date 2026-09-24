package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.AdminUserSummary
import dev.alllexey.itmowidgets.backend.repositories.UserGroupRow
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.repositories.UserSummaryRow
import dev.alllexey.itmowidgets.core.model.GroupData
import org.springframework.stereotype.Component
import java.util.UUID

/** Stored identities of many users in two queries, for admin lists. Callers check roles. */
@Component
class AdminUserSummaries(private val users: UserRepository) {
    fun of(ids: Collection<UUID>): Map<UUID, AdminUserSummary> {
        if (ids.isEmpty()) return emptyMap()
        val distinct = ids.toSet()
        return of(users.findSummaryRows(distinct))
    }

    fun of(rows: List<UserSummaryRow>): Map<UUID, AdminUserSummary> {
        if (rows.isEmpty()) return emptyMap()
        val groups = users.findGroupRows(rows.map { it.id }.toSet()).groupBy { it.userId }
        return rows.associate { row -> row.id to AdminUserSummary(row.isu, row.name ?: "", row.pictureUrl, groups[row.id].orEmpty().sorted()) }
    }

    /** The same order as `UserPrivacyService.userDataFor`: the highest course is the best guess at the current one. */
    private fun List<UserGroupRow>.sorted(): List<GroupData> = sortedWith(compareByDescending<UserGroupRow> { it.course }.thenBy { it.name })
        .map { GroupData(it.name, it.course, it.facultyShortName) }
}
