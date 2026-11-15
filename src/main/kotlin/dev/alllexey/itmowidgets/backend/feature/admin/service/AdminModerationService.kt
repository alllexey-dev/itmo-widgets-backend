package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminCaseItem
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminCaseOutcome
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPage
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminRestriction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationCaseRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationDecisionRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationReportRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.UserRestrictionRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationSettingsService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationTargets
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCase
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationDecisionRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationSettings
import dev.alllexey.itmowidgets.backend.feature.moderation.web.SubjectLinkTarget
import dev.alllexey.itmowidgets.backend.feature.moderation.web.TeacherReviewTarget
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherNamesService
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * The web moderation queue. Moderators and admins see cases and restrictions; the moderation policy is
 * admin-only and audited. Teacher names are resolved after the database transaction.
 */
@Service
class AdminModerationService(
    private val access: AdminAccess,
    private val moderation: ModerationService,
    private val settings: ModerationSettingsService,
    private val restrictions: RestrictionService,
    private val cases: ModerationCaseRepository,
    private val decisions: ModerationDecisionRepository,
    private val reports: ModerationReportRepository,
    private val restrictionRows: UserRestrictionRepository,
    private val targets: ModerationTargets,
    private val summaries: AdminUserSummaries,
    private val restrictionViews: AdminRestrictionViews,
    private val teacherNames: TeacherNamesService,
    private val audit: AdminAuditService,
    transactions: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val readOnly = TransactionTemplate(transactions).apply { isReadOnly = true }

    /**
     * Open cases oldest first (the queue order); closed cases most recently resolved first. A page takes a fixed number
     * of queries in one read-only transaction; teacher names are looked up after it.
     */
    fun cases(
        moderatorId: UUID,
        status: ModerationCaseStatus,
        reason: ModerationCaseReason?,
        page: Int,
        size: Int,
    ): AdminPage<AdminCaseItem> {
        val result = readOnly.execute { casePage(moderatorId, status, reason, page, size) }!!
        val names = teacherNames.names(result.items.mapNotNull { it.review?.teacherIsu })
        return result.copy(
            items = result.items.map { item ->
                item.review?.let { item.copy(review = it.copy(teacherName = names[it.teacherIsu])) } ?: item
            },
        )
    }

    private fun casePage(
        moderatorId: UUID,
        status: ModerationCaseStatus,
        reason: ModerationCaseReason?,
        page: Int,
        size: Int,
    ): AdminPage<AdminCaseItem> {
        access.requireModerator(moderatorId)
        val order = if (status == ModerationCaseStatus.OPEN) {
            Sort.by("openedAt", "id")
        } else {
            Sort.by(Sort.Direction.DESC, "resolvedAt", "id")
        }
        val result = cases.findPage(status, reason, AdminPage.request(page, size, order))
        val byType = result.content.groupBy { it.targetType }
        val described = byType.flatMap { (type, rows) -> targets.forType(type).summaries(rows.map { it.targetId }).entries }
            .associate { it.key to it.value }
        val reportCounts = byType.flatMap { (type, rows) -> reports.countActiveByTargets(type, rows.map { it.targetId }) }
            .associate { it.targetId to it.count }
        val authors = summaries.of(described.values.map { it.ownerId })
        val outcomes = outcomes(result.content.filter { it.status == ModerationCaseStatus.RESOLVED }.map { it.id })
        return AdminPage.of(
            result,
            result.content.map { case ->
                val target = described[case.targetId]
                AdminCaseItem(
                    case.id, case.targetType, case.status, case.reason, case.openedAt, case.resolvedAt,
                    target?.revision, target?.link, target?.review, target?.let { authors[it.ownerId] }, reportCounts[case.targetId] ?: 0,
                    outcomes[case.id],
                )
            },
        )
    }

    /** One query for the page, none for a page without resolved cases. */
    private fun outcomes(caseIds: List<UUID>): Map<UUID, AdminCaseOutcome> {
        if (caseIds.isEmpty()) return emptyMap()
        return decisions.findOutcomes(caseIds, RESOLVING).associate {
            it.caseId to AdminCaseOutcome(it.action, it.actor, it.decidedAt, it.moderatorIsu, it.moderatorName, it.note)
        }
    }

    fun case(moderatorId: UUID, caseId: UUID): ModerationCase = withTeacherName(moderation.case(moderatorId, caseId))

    fun decide(moderatorId: UUID, caseId: UUID, request: ModerationDecisionRequest): ModerationCase =
        withTeacherName(moderation.decide(moderatorId, caseId, request))

    /** Newest first; [query] matches an ISU prefix or a part of the user's name, as the user search does without groups. */
    @Transactional(readOnly = true)
    fun restrictions(
        moderatorId: UUID,
        isu: Int?,
        query: String?,
        activeOnly: Boolean,
        page: Int,
        size: Int,
    ): AdminPage<AdminRestriction> {
        access.requireModerator(moderatorId)
        val search = AdminSearch.of(query)
        val result = restrictionRows.findAdminPage(
            isu,
            activeOnly,
            clock.instant(),
            AdminPage.request(page, size),
            search?.pattern,
            search?.isuPrefix.orEmpty(),
        )
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

    private fun withTeacherName(case: ModerationCase): ModerationCase = when (val target = case.target) {
        is TeacherReviewTarget -> case.copy(
            target = target.copy(review = target.review.copy(teacherName = teacherNames.name(target.review.teacherIsu))),
        )

        is SubjectLinkTarget, null -> case
    }

    private companion object {
        val RESOLVING = ModerationAction.entries.filter { it.resolvesCase }.map { it.name }
    }
}
