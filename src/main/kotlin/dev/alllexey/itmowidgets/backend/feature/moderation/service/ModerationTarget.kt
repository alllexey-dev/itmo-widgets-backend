package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminLinkSummary
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReviewSummary
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkRevision
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.TargetState
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCaseTarget
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * A kind of case target. The shared machinery owns cases, decisions, the revert window, authorization and the audit;
 * each target owns its rows: [apply] carries a decision out on them and [revert] undoes a revertible one.
 */
interface ModerationTarget {
    fun targetType(): ModerationTargetType
    fun ownerId(targetId: UUID): UUID
    fun isReportable(targetId: UUID, reporterId: UUID): Boolean

    /** What a revertible decision would change on [targetId] (see [TargetState.after]); null when it was deleted. */
    fun state(targetId: UUID): TargetState?

    fun apply(action: ModerationAction, targetId: UUID, decision: ModerationDecisionEntity)

    /**
     * The flow an APPROVE of [targetId] records when a moderator asks to show it to [flowId] instead: null when that is
     * the flow it already has. Throws when [flowId] is not one the target's author could pick; only FLOW link revisions
     * with stored flow options have any.
     */
    fun approvalFlow(targetId: UUID, flowId: Long): Long? = throw InvalidRequestDataException("The target has no flow to change")

    /**
     * Undoes the revertible [decision] on [targetId]: the target takes back the state recorded in `decision.prior`, and
     * whatever else [apply] changed returns too. The caller holds the locks of a decision and has checked that
     * [state] is still what the decision left.
     */
    fun revert(targetId: UUID, decision: ModerationDecisionEntity)
    fun canAutoApprove(targetId: UUID): Boolean = true
    fun describe(targetId: UUID, viewerId: UUID): ModerationCaseTarget

    /** Queue rows of existing targets in a fixed number of queries; deleted targets are absent. */
    fun summaries(targetIds: Collection<UUID>): Map<UUID, CaseTargetSummary> = emptyMap()

    /** What the author's notices name, in a fixed number of queries; deleted targets are absent. */
    fun noticeSubjects(targetIds: Collection<UUID>): Map<UUID, NoticeSubject> = emptyMap()
}

/** The link or the review a notice is about, as it is now; the caller names a review's teacher after its transaction. */
data class NoticeSubject(val link: Link? = null, val review: Review? = null) {
    /** [title] and [host] are of the decided revision. */
    data class Link(val id: UUID, val subjectId: Long, val subjectName: String, val periodKey: String, val title: String?, val host: String)

    /** [subjectTitle] is of the decided revision. */
    data class Review(val id: UUID, val teacherIsu: Int, val subjectTitle: String?)
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
