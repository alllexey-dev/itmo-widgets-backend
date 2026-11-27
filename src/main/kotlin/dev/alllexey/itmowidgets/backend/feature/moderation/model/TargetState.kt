package dev.alllexey.itmowidgets.backend.feature.moderation.model

import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import java.time.Instant

/** The status of a case target's revision in the shared machinery; each target maps its own revision status onto it. */
enum class TargetRevisionStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }

/**
 * What a single-target decision changes on its target, and when the owner last changed it: the reviewed revision's
 * outcome, and the hiding and the last owner edit of the revision's link or review. Mapped as the `prior_*` columns of
 * `moderation_decisions`, where a revertible decision records the state it found ([DecisionPrior]).
 */
@Embeddable
data class TargetState(
    @Enumerated(EnumType.STRING) @Column(name = "prior_revision_status", length = 16) val revisionStatus: TargetRevisionStatus,
    @Column(name = "prior_decided_at") val decidedAt: Instant?,
    @Column(name = "prior_note", length = 500) val note: String?,
    @Column(name = "prior_hidden_at") val hiddenAt: Instant?,
    /** The link's or review's `updated_at`: only its owner moves it, a decision never does. */
    @Column(name = "prior_edited_at") val editedAt: Instant,
    /** The schedule flow a link revision is shown to; null for other targets and revisions without a flow. */
    @Column(name = "prior_flow_id") val flowId: Long? = null,
) {
    /**
     * The state [decision] leaves when it takes [action] on this one: the single rule by which a target applies a
     * revertible action and by which a revert tells that nothing changed the target since the decision. An APPROVE
     * with a [ModerationDecisionEntity.flowId] also moves the revision to that flow.
     */
    fun after(action: ModerationAction, decision: ModerationDecisionEntity): TargetState = when (action) {
        ModerationAction.APPROVE -> when (revisionStatus) {
            TargetRevisionStatus.PENDING -> decided(TargetRevisionStatus.APPROVED, decision)
            TargetRevisionStatus.APPROVED -> this
            else -> throw BusinessRuleException("Only a pending revision can be approved")
        }.let { approved -> decision.flowId?.let { approved.copy(flowId = it) } ?: approved }

        ModerationAction.REJECT -> when (revisionStatus) {
            TargetRevisionStatus.PENDING, TargetRevisionStatus.APPROVED -> decided(TargetRevisionStatus.REJECTED, decision)
            else -> throw BusinessRuleException("Only a pending or approved revision can be rejected")
        }

        ModerationAction.HIDE -> copy(hiddenAt = hiddenAt ?: decision.createdAt)

        ModerationAction.RESTORE -> copy(hiddenAt = null)

        ModerationAction.DISMISS -> this

        ModerationAction.RESTRICT_USER, ModerationAction.HIDE_ALL_BY_USER ->
            throw IllegalArgumentException("$action does not act on one target")
    }

    private fun decided(status: TargetRevisionStatus, decision: ModerationDecisionEntity) =
        copy(revisionStatus = status, decidedAt = decision.createdAt, note = decision.note)
}

/** What a revertible decision replaced: its target's state and its case's `resolvedAt` (null when the case was open). */
@Embeddable
data class DecisionPrior(@Embedded val target: TargetState, @Column(name = "prior_case_resolved_at") val caseResolvedAt: Instant?)
