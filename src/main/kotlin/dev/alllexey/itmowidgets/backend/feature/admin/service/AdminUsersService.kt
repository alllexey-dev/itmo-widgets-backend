package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.activity.service.UserLastSeen
import dev.alllexey.itmowidgets.backend.feature.admin.persistence.AdminUserSearchRepository
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAgentAccess
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClient
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPage
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminUserDetail
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminUserItem
import dev.alllexey.itmowidgets.backend.feature.clients.persistence.ClientInstanceRepository
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientAccessService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.feature.links.persistence.SubjectLinkRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.UserRestrictionRepository
import dev.alllexey.itmowidgets.backend.feature.push.persistence.DeviceRepository
import dev.alllexey.itmowidgets.backend.feature.social.persistence.FriendshipRepository
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRoleRepository
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
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
    private val userSearch: AdminUserSearchRepository,
    private val roles: UserRoleRepository,
    private val devices: DeviceRepository,
    private val friendships: FriendshipRepository,
    private val links: SubjectLinkRepository,
    private val restrictions: UserRestrictionRepository,
    private val summaries: AdminUserSummaries,
    private val restrictionViews: AdminRestrictionViews,
    private val audit: AdminAuditService,
    private val clientAccess: ClientAccessService,
    private val clientInstances: ClientInstanceRepository,
    private val connections: ConnectionService,
    private val lastSeen: UserLastSeen,
    private val clock: Clock,
) {
    /**
     * Newest users first. A query matches an ISU prefix, or a part of the name or of a stored group name, ignoring case;
     * [role] and [restricted] narrow the list further, and the total counts the narrowed list.
     */
    @Transactional(readOnly = true)
    fun search(adminId: UUID, query: String?, role: UserRole?, restricted: Boolean?, page: Int, size: Int): AdminPage<AdminUserItem> {
        access.requireAdmin(adminId)
        val search = AdminSearch.of(query)
        val now = clock.instant()
        val result = userSearch.search(search?.pattern, search?.isuPrefix.orEmpty(), role, restricted, now, AdminPage.request(page, size))
        val ids = result.content.map { it.id }
        val found = summaries.of(result.content)
        val userRoles = rolesOf(ids)
        val inForce = if (ids.isEmpty()) emptyMap() else restrictions.countActiveByUsers(ids, now).associate { it.userId to it.count }
        return AdminPage.of(
            result,
            result.content.map { row ->
                val summary = found.getValue(row.id)
                AdminUserItem(
                    summary.isu,
                    summary.name,
                    summary.pictureUrl,
                    summary.groups,
                    userRoles[row.id].orEmpty(),
                    row.createdAt,
                    inForce[row.id]?.toInt() ?: 0,
                )
            },
        )
    }

    @Transactional(readOnly = true)
    fun detail(adminId: UUID, isu: Int): AdminUserDetail {
        access.requireAdmin(adminId)
        val row = users.findSummaryRows(listOf(userId(isu))).single()
        val stored = summaries.of(listOf(row)).getValue(row.id)
        val restrictionRows = restrictions.findAdminPage(isu, false, clock.instant(), PageRequest.of(0, RESTRICTION_HISTORY)).content
        return AdminUserDetail(
            user = stored,
            roles = rolesOf(listOf(row.id))[row.id].orEmpty(),
            groups = stored.groups,
            createdAt = row.createdAt,
            devices = devices.findAdminDevices(row.id),
            friendsCount = friendships.countFriendsOf(row.id),
            linksCount = links.countByOwnerId(row.id),
            restrictions = restrictionViews.of(restrictionRows),
            lastSeen = lastSeen.of(row.id),
            agentAccess = clientAccess.isAgentGranted(row.id),
            clients = clientsOf(row.id),
        )
    }

    private fun clientsOf(userId: UUID): List<AdminClient> {
        val rows = clientInstances.findByUserIdOrderByLastSeenAtDesc(userId)
        val names = connections.namesOf(rows.filter { !it.kind.isApp }.map { it.instanceId })
        return rows.map {
            AdminClient(
                it.kind, if (it.kind.isApp) null else names[it.instanceId], it.version, it.build, it.channel,
                it.source, it.commit, it.base, it.custom, it.os, it.browser, it.firstSeenAt, it.lastSeenAt,
            )
        }
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

    /** Idempotent; only an actual change is audited. */
    @Transactional
    fun setAgentAccess(adminId: UUID, isu: Int, enabled: Boolean): AdminAgentAccess {
        access.requireAdmin(adminId)
        val userId = userId(isu)
        if (clientAccess.setAgentGranted(userId, enabled)) {
            val action = if (enabled) AdminAuditAction.AGENT_ACCESS_GRANTED else AdminAuditAction.AGENT_ACCESS_REVOKED
            audit.record(adminId, action, "user:$isu", null)
        }
        return AdminAgentAccess(enabled)
    }

    private fun managedRole(role: String): UserRole {
        if (role != UserRole.MODERATOR.name) throw InvalidRequestDataException("Only the MODERATOR role is managed here")
        return UserRole.MODERATOR
    }

    private fun userId(isu: Int): UUID = users.findIdByIsu(isu) ?: throw NotFoundException("User not found")

    /** Role names in enum order, the same as `/api/client/me`. */
    private fun rolesOf(userIds: List<UUID>): Map<UUID, List<String>> = if (userIds.isEmpty()) {
        emptyMap()
    } else {
        roles.findRoleIdsOf(userIds).groupBy({ it.userId }, { it.role }).mapValues { (_, list) -> list.sorted().map { it.name } }
    }

    private companion object {
        const val RESTRICTION_HISTORY = 50
    }
}
