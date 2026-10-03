package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminLinkSummary
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReviewSummary
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkRevision
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCaseTarget
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

interface ModerationTarget {
    fun targetType(): ModerationTargetType
    fun ownerId(targetId: UUID): UUID
    fun isReportable(targetId: UUID, reporterId: UUID): Boolean
    fun apply(action: ModerationAction, targetId: UUID, decision: ModerationDecisionEntity)
    fun canAutoApprove(targetId: UUID): Boolean = true
    fun describe(targetId: UUID, viewerId: UUID): ModerationCaseTarget
    /** Queue rows of existing targets in a fixed number of queries; deleted targets are absent. */
    fun summaries(targetIds: Collection<UUID>): Map<UUID, CaseTargetSummary> = emptyMap()
}

/** What a queue row shows about one target; the caller resolves [ownerId] with the other authors of the page. */
data class CaseTargetSummary(
    val revision: SubjectLinkRevision? = null,
    val link: AdminLinkSummary? = null,
    val ownerId: UUID,
    val review: AdminReviewSummary? = null,
)

/** Resolve targets only during a request: targets themselves depend on the moderation services. */
@Service
class ModerationTargets(private val targets: org.springframework.beans.factory.ObjectProvider<ModerationTarget>) {
    fun forType(type: ModerationTargetType): ModerationTarget = targets.orderedStream()
        .filter { it.targetType() == type }.findFirst().orElseThrow { InvalidRequestDataException("Unsupported moderation target") }
}
