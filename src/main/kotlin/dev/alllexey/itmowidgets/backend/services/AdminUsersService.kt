package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.AdminPage
import dev.alllexey.itmowidgets.backend.dto.AdminUserDetail
import dev.alllexey.itmowidgets.backend.dto.AdminUserItem
import dev.alllexey.itmowidgets.backend.dto.UserCapabilities
import dev.alllexey.itmowidgets.backend.dto.UserData
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.UserRole
import dev.alllexey.itmowidgets.backend.repositories.DeviceRepository
import dev.alllexey.itmowidgets.backend.repositories.FriendshipRepository
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRestrictionRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRoleRepository
import dev.alllexey.itmowidgets.backend.repositories.WebSessionRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** Admin-only user search, user details and the moderator role. ADMIN itself is granted only by SQL. */
@Service
class AdminUsersService(
    private val access: AdminAccess,
    private val users: UserRepository,
    private val roles: UserRoleRepository,
    private val devices: DeviceRepository,
    private val friendships: FriendshipRepository,
    private val links: SubjectLinkRepository,
    private val restrictions: UserRestrictionRepository,
    private val webSessions: WebSessionRepository,
    private val summaries: AdminUserSummaries,
    private val restrictionViews: AdminRestrictionViews,
    private val currentGroups: CurrentStudyGroupsService,
    private val audit: AdminAuditService,
    private val clock: Clock,
) {
    /** Newest users first. A query matches an ISU prefix, or a part of the name or of a stored group name, ignoring case. */
    @Transactional(readOnly = true)
    fun search(adminId: UUID, query: String?, page: Int, size: Int): AdminPage<AdminUserItem> {
        access.requireAdmin(adminId)
        val text = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > QUERY_LENGTH) throw InvalidRequestDataException("Search query is too long")
        val pattern = text?.let { "%${it.escapeLike()}%" }
        val isuPrefix = text?.takeIf { it.all(Char::isDigit) }?.let { "$it%" } ?: ""
        val result = users.search(pattern, isuPrefix, AdminPage.request(page, size))
        val found = summaries.of(result.content)
        val userRoles = rolesOf(result.content.map { it.id })
        return AdminPage.of(result, result.content.map { row ->
            val summary = found.getValue(row.id)
            AdminUserItem(summary.isu, summary.name, summary.pictureUrl, summary.groups, userRoles[row.id].orEmpty(), row.createdAt)
        })
    }

    /** Not transactional: current study groups are read from MyITMO after the database reads. */
    fun detail(adminId: UUID, isu: Int): AdminUserDetail {
        access.requireAdmin(adminId)
        val row = users.findSummaryRows(listOf(userId(isu))).single()
        val stored = summaries.of(listOf(row)).getValue(row.id)
        val lastSeen = listOfNotNull(devices.findLastLogin(row.id), webSessions.findLastSeen(row.id)).maxOrNull()
        val restrictionRows = restrictions.findAdminPage(isu, false, clock.instant(), PageRequest.of(0, RESTRICTION_HISTORY)).content
        val current = currentGroups.userData(UserData(stored.isu, stored.name, stored.pictureUrl, stored.groups,
            UserCapabilities(canViewSchedule = false, canViewSport = false, canViewFriends = false))).groups
        return AdminUserDetail(
            user = stored.copy(groups = current),
            roles = rolesOf(listOf(row.id))[row.id].orEmpty(),
            groups = stored.groups,
            createdAt = row.createdAt,
            devices = devices.findAdminDevices(row.id),
            friendsCount = friendships.countFriendsOf(row.id),
            linksCount = links.countByOwnerId(row.id),
            restrictions = restrictionViews.of(restrictionRows),
            lastSeen = lastSeen,
        )
    }

    /** Idempotent; only an actual change is audited. Returns the user's roles afterwards. */
    @Transactional
    fun grant(adminId: UUID, isu: Int, role: String): List<String> {
        access.requireAdmin(adminId)
        val managed = managedRole(role)
        val userId = userId(isu)
        if (roles.grant(userId, managed.name, clock.instant()) > 0) {
            audit.record(adminId, AdminAuditAction.ROLE_GRANTED, "user:$isu", "role ${managed.name}")
        }
        return rolesOf(listOf(userId))[userId].orEmpty()
    }

    @Transactional
    fun revoke(adminId: UUID, isu: Int, role: String): List<String> {
        access.requireAdmin(adminId)
        val managed = managedRole(role)
        val userId = userId(isu)
        if (roles.revoke(userId, managed) > 0) {
            audit.record(adminId, AdminAuditAction.ROLE_REVOKED, "user:$isu", "role ${managed.name}")
        }
        return rolesOf(listOf(userId))[userId].orEmpty()
    }

    private fun managedRole(role: String): UserRole {
        if (role != UserRole.MODERATOR.name) throw InvalidRequestDataException("Only the MODERATOR role is managed here")
        return UserRole.MODERATOR
    }

    private fun userId(isu: Int): UUID = users.findIdByIsu(isu) ?: throw NotFoundException("User not found")

    /** Role names in enum order, the same as `/api/web/auth/me`. */
    private fun rolesOf(userIds: List<UUID>): Map<UUID, List<String>> =
        if (userIds.isEmpty()) emptyMap()
        else roles.findRoleIdsOf(userIds).groupBy({ it.userId }, { it.role }).mapValues { (_, list) -> list.sorted().map { it.name } }

    private fun String.escapeLike(): String = replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private companion object {
        const val QUERY_LENGTH = 100
        const val RESTRICTION_HISTORY = 50
    }
}
