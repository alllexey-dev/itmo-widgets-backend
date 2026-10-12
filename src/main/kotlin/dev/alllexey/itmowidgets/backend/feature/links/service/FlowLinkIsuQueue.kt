package dev.alllexey.itmowidgets.backend.feature.links.service

import dev.alllexey.itmowidgets.backend.feature.links.model.LinkRevisionStatus
import dev.alllexey.itmowidgets.backend.feature.links.persistence.SubjectLinkRevisionRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationService
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** A due ISU check of a pending FLOW revision; [dueAt] identifies the check. */
data class FlowLinkCheck(val revisionId: UUID, val authorIsu: Int, val flowId: Long, val dueAt: Instant, val attempts: Int)

/**
 * The ISU check queue of pending FLOW revisions in `subject_link_revisions`; `IsuVerificationService` runs it.
 * Every call commits on its own. ISU only ever approves: every other outcome leaves the revision to moderators.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class FlowLinkIsuQueue(private val revisions: SubjectLinkRevisionRepository, private val moderation: ModerationService) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun due(now: Instant, limit: Int): List<FlowLinkCheck> = revisions.findIsuDue(now, Limit.of(limit)).map {
        FlowLinkCheck(it.id, it.link.owner.isu, checkNotNull(it.flowId), checkNotNull(it.isuCheckDueAt), it.isuCheckAttempts)
    }

    /**
     * Publishes the revision with a POLICY decision when its check is still the one started and its SUBMISSION case is
     * still open; a moderator's decision, a newer revision or a hidden link wins over ISU.
     */
    fun approve(check: FlowLinkCheck, flowSize: Int): Boolean {
        moderation.lock(TYPE)
        val revision = revisions.findById(check.revisionId).orElse(null) ?: return false
        if (revision.status != LinkRevisionStatus.PENDING || revision.isuCheckDueAt != check.dueAt) return false
        revision.isuCheckDueAt = null
        revisions.save(revision)
        return moderation.approveOpenByPolicy(TYPE, revision.id, "ISU check: the author is in flow ${check.flowId} of $flowSize members")
    }

    /** Ends the check without a decision: the revision waits for a moderator. */
    fun leaveToModerators(check: FlowLinkCheck): Boolean = revisions.postponeIsuCheck(check.revisionId, check.dueAt, null, 0) == 1

    fun postpone(check: FlowLinkCheck, until: Instant): Boolean = revisions.postponeIsuCheck(check.revisionId, check.dueAt, until, 1) == 1

    fun postponeAllDue(now: Instant, until: Instant): Int = revisions.postponeAllIsuDue(now, until)

    fun rescheduleAllPending(now: Instant): Int = revisions.rescheduleAllIsuPending(now)

    private companion object {
        val TYPE = ModerationTargetType.SUBJECT_RESOURCE
    }
}
