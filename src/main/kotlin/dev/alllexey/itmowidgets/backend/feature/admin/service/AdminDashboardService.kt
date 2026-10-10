package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.persistence.LabelCount
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDashboard
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDashboardDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDashboardTotals
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.links.persistence.SubjectLinkRepository
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationCaseRepository
import dev.alllexey.itmowidgets.backend.feature.push.persistence.DeviceRepository
import dev.alllexey.itmowidgets.backend.feature.social.model.FriendshipEntity
import dev.alllexey.itmowidgets.backend.feature.social.persistence.FriendshipRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.feature.weblogin.persistence.WebSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Admin-only totals and 30 daily points; every figure is an aggregate query, no rows are loaded. */
@Service
class AdminDashboardService(
    private val access: AdminAccess,
    private val users: UserRepository,
    private val devices: DeviceRepository,
    private val webSessions: WebSessionRepository,
    private val friendships: FriendshipRepository,
    private val links: SubjectLinkRepository,
    private val cases: ModerationCaseRepository,
    private val autoSign: SportAutoSignEntryRepository,
    private val freeSign: SportFreeSignEntryRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun dashboard(adminId: UUID): AdminDashboard {
        access.requireAdmin(adminId)
        val now = clock.instant()
        val week = now.minus(Duration.ofDays(7))
        val linkStatuses = links.countByOwnerStatus().associate { it.label to it.total }
        val platforms = devices.countByPlatform().associate { it.label to it.total }
        val totals = AdminDashboardTotals(
            users = users.count(),
            newUsers7d = users.countByCreatedAtGreaterThanEqual(week),
            activeDevices7d = devices.countByLastLoginGreaterThanEqual(week),
            activeDevices30d = devices.countByLastLoginGreaterThanEqual(now.minus(Duration.ofDays(30))),
            devicesByPlatform = AppPlatform.entries.associateWith { platforms[it.name] ?: 0 },
            webSessions7d = webSessions.countByCreatedAtGreaterThanEqual(week),
            friendships = friendships.countByStatus(FriendshipEntity.Status.ACCEPTED),
            links = SubjectLinkStatus.entries.associateWith { linkStatuses[it.name] ?: 0 },
            openCases = cases.countByStatus(ModerationCaseStatus.OPEN),
            activeAutoSignEntries = autoSign.countActive(),
            activeFreeSignEntries = freeSign.countActive(),
        )
        val today = LocalDate.ofInstant(now, ZONE)
        val first = today.minusDays(DAYS - 1L)
        val from = first.atStartOfDay(ZONE).toInstant()
        val newUsers = users.countCreatedPerDay(from, ZONE.id).byDay()
        val activeDevices = devices.countLastLoginPerDay(from, ZONE.id).byDay()
        val createdLinks = links.countCreatedPerDay(from, ZONE.id).byDay()
        val days = (0 until DAYS).map { offset ->
            val date = first.plusDays(offset.toLong())
            AdminDashboardDay(date, newUsers[date] ?: 0, activeDevices[date] ?: 0, createdLinks[date] ?: 0)
        }
        return AdminDashboard(totals, days)
    }

    private fun List<LabelCount>.byDay(): Map<LocalDate, Long> = associate { LocalDate.parse(it.label) to it.total }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
        const val DAYS = 30
    }
}
