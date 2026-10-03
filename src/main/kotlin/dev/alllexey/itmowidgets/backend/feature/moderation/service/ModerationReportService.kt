package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationReportEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ReportReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationReportRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationReport
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationReportRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.toDto
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class ModerationReportService(
    private val reports: ModerationReportRepository,
    private val users: UserRepository,
    private val restrictions: RestrictionService,
    private val settings: ModerationSettingsService,
    private val moderation: ModerationService,
    private val targets: ModerationTargets,
    private val clock: Clock,
) {
    @Transactional
    fun report(reporterId: UUID, targetType: ModerationTargetType, targetId: UUID, request: ModerationReportRequest): List<ModerationReport> {
        if (targetType == ModerationTargetType.TEACHER_REVIEW && request.reason !in ReportReason.REVIEW_REASONS) {
            throw InvalidRequestDataException("Unsupported report reason")
        }
        restrictions.require(reporterId, RestrictionCapability.REPORT)
        moderation.lock(targetType)
        if (request.comment != null && request.comment.length > 500) throw InvalidRequestDataException("Report comment is too long")
        // Serialize a reporter's quota across different targets, not only duplicate reports.
        users.lockById(reporterId) ?: throw NotFoundException("User not found")
        val now = clock.instant()
        val policy = settings.policy(targetType)
        if (reports.countByReporterIdAndCreatedAtAfter(reporterId, now.minusSeconds(86400)) >= policy.dailyReportLimit) {
            throw BusinessRuleException("Daily report limit reached")
        }
        if (!targets.forType(targetType).isReportable(targetId, reporterId)) throw BusinessRuleException("Target cannot be reported")
        if (reports.existsByTargetAndReporter(targetType, targetId, reporterId)) throw BusinessRuleException("Already reported")
        val reporter = users.findById(reporterId).orElseThrow { NotFoundException("User not found") }
        reports.saveAndFlush(ModerationReportEntity(targetType = targetType, targetId = targetId, reporter = reporter,
            reason = request.reason, comment = request.comment?.trim()?.takeIf { it.isNotEmpty() }, createdAt = now))
        if (reports.countActiveDistinctReporters(targetType, targetId) >= policy.reportThreshold) {
            moderation.openCase(targetType, targetId, ModerationCaseReason.REPORTS)
        }
        return activeFor(targetType, targetId)
    }

    @Transactional
    fun dismissAll(targetType: ModerationTargetType, targetId: UUID) { reports.dismissAll(targetType, targetId, clock.instant()) }

    @Transactional
    fun deleteAllFor(targetType: ModerationTargetType, targetId: UUID) { reports.deleteAllByTarget(targetType, targetId) }

    @Transactional(readOnly = true)
    fun activeFor(targetType: ModerationTargetType, targetId: UUID): List<ModerationReport> =
        reports.findActive(targetType, targetId).map { it.toDto() }
}
