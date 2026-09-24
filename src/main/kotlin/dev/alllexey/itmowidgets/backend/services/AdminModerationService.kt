package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.AdminCaseItem
import dev.alllexey.itmowidgets.backend.dto.AdminPage
import dev.alllexey.itmowidgets.backend.dto.AdminRestriction
import dev.alllexey.itmowidgets.backend.dto.ModerationCase
import dev.alllexey.itmowidgets.backend.dto.ModerationDecisionRequest
import dev.alllexey.itmowidgets.backend.dto.ModerationSettings
import dev.alllexey.itmowidgets.backend.dto.SubjectLinkTarget
import dev.alllexey.itmowidgets.backend.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.repositories.ModerationCaseRepository
import dev.alllexey.itmowidgets.backend.repositories.ModerationReportRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRestrictionRepository
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The web moderation queue. Moderators and admins see cases and restrictions; the moderation policy is
 * admin-only and audited. Current study groups are resolved after the database transaction.
 */
@Service
class AdminModerationService(
    private val access: AdminAccess,
    private val moderation: ModerationService,
    private val settings: ModerationSettingsService,
    private val restrictions: RestrictionService,
    private val cases: ModerationCaseRepository,
    private val reports: ModerationReportRepository,
    private val restrictionRows: UserRestrictionRepository,
    private val targets: ModerationTargets,
    private val summaries: AdminUserSummaries,
    private val restrictionViews: AdminRestrictionViews,
    private val currentGroups: CurrentStudyGroupsService,
    private val audit: AdminAuditService,
    private val clock: Clock,
) {
    /** Open cases oldest first (the queue order); closed cases most recently resolved first. */
    @Transactional(readOnly = true)
    fun cases(moderatorId: UUID, status: ModerationCaseStatus, reason: ModerationCaseReason?, page: Int, size: Int): AdminPage<AdminCaseItem> {
        access.requireModerator(moderatorId)
        val order = if (status == ModerationCaseStatus.OPEN) Sort.by("openedAt", "id")
        else Sort.by(Sort.Direction.DESC, "resolvedAt", "id")
        val result = cases.findPage(status, reason, AdminPage.request(page, size, order))
        val byType = result.content.groupBy { it.targetType }
        val described = byType.flatMap { (type, rows) -> targets.forType(type).summaries(rows.map { it.targetId }).entries }
            .associate { it.key to it.value }
        val reportCounts = byType.flatMap { (type, rows) -> reports.countActiveByTargets(type, rows.map { it.targetId }) }
            .associate { it.targetId to it.count }
        val authors = summaries.of(described.values.map { it.ownerId })
        return AdminPage.of(result, result.content.map { case ->
            val target = described[case.targetId]
            AdminCaseItem(case.id, case.targetType, case.status, case.reason, case.openedAt, case.resolvedAt,
                target?.revision, target?.link, target?.let { authors[it.ownerId] }, reportCounts[case.targetId] ?: 0)
        })
    }

    fun case(moderatorId: UUID, caseId: UUID): ModerationCase = withCurrentGroups(moderation.case(moderatorId, caseId))

    fun decide(moderatorId: UUID, caseId: UUID, request: ModerationDecisionRequest): ModerationCase =
        withCurrentGroups(moderation.decide(moderatorId, caseId, request))

    @Transactional(readOnly = true)
    fun restrictions(moderatorId: UUID, isu: Int?, activeOnly: Boolean, page: Int, size: Int): AdminPage<AdminRestriction> {
        access.requireModerator(moderatorId)
        val result = restrictionRows.findAdminPage(isu, activeOnly, clock.instant(), AdminPage.request(page, size))
        return AdminPage.of(result, restrictionViews.of(result.content))
    }

    fun revokeRestriction(moderatorId: UUID, restrictionId: UUID) = restrictions.revoke(restrictionId, moderatorId)

    @Transactional(readOnly = true)
    fun settings(adminId: UUID): ModerationSettings {
        access.requireAdmin(adminId)
        return settings.settings(adminId)
    }

    /** Admin-only for both the web and the legacy route; every changed key is audited with its old and new value. */
    @Transactional
    fun updateSettings(adminId: UUID, request: ModerationSettings): ModerationSettings {
        access.requireAdmin(adminId)
        // The same locks the update takes, so the recorded "before" is the state it replaces.
        ModerationTargetType.entries.forEach(moderation::lock)
        val before = settings.settings(adminId).policies
        val after = settings.update(adminId, request)
        val changes = after.policies.flatMap { (type, policy) ->
            val previous = before[type]?.toKeys(type).orEmpty()
            policy.toKeys(type).filter { (key, value) -> previous[key] != value }.map { (key, value) -> "$key ${previous[key]} -> $value" }
        }
        if (changes.isNotEmpty()) {
            audit.record(adminId, AdminAuditAction.MODERATION_SETTINGS_CHANGED, "moderation-settings", changes.joinToString("; "))
        }
        return after
    }

    private fun withCurrentGroups(case: ModerationCase): ModerationCase = when (val target = case.target) {
        is SubjectLinkTarget -> case.copy(target = target.copy(author = currentGroups.userData(target.author),
            link = target.link.copy(author = target.link.author?.let(currentGroups::userData))))
        null -> case
    }
}
