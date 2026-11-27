package dev.alllexey.itmowidgets.backend.feature.moderation.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** What a decision the author should know of did to their link or review (docs/contracts/notifications.md). */
enum class ModerationOutcome {
    APPROVED,
    REJECTED,
    HIDDEN,
    RESTORED,
    ;

    companion object {
        /**
         * The outcome the author is told of when [action] finds the target in [prior] (null for a POLICY decision), or
         * null when nothing changed for them: an APPROVE of an already approved revision (a report kept), a HIDE of a
         * hidden or a RESTORE of a shown target, DISMISS and the decisions about the user rather than one target.
         */
        fun of(action: ModerationAction, prior: TargetState?): ModerationOutcome? = when (action) {
            ModerationAction.APPROVE -> APPROVED.takeIf { prior == null || prior.revisionStatus == TargetRevisionStatus.PENDING }
            ModerationAction.REJECT -> REJECTED
            ModerationAction.HIDE -> HIDDEN.takeIf { prior == null || prior.hiddenAt == null }
            ModerationAction.RESTORE -> RESTORED.takeIf { prior == null || prior.hiddenAt != null }
            ModerationAction.DISMISS, ModerationAction.RESTRICT_USER, ModerationAction.HIDE_ALL_BY_USER -> null
        }
    }
}

/**
 * One notice for the author of a case target, keyed by its decision. Only [sentAt] changes; a revert deletes the row
 * before [dueAt], which is the end of the decision's revert window.
 */
@Entity
@Table(name = "moderation_notices")
class ModerationNoticeEntity(
    @Id val decisionId: UUID,
    @Column(nullable = false) val recipientId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) val targetType: ModerationTargetType,
    @Column(nullable = false) val targetId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val outcome: ModerationOutcome,
    /** The moderator's note of a rejection, which the author also sees as `reviewNote`; null for other outcomes. */
    @Column(length = 500) val note: String?,
    @Column(nullable = false) val decidedAt: Instant,
    @Column(nullable = false) val dueAt: Instant,
    var sentAt: Instant? = null,
)
