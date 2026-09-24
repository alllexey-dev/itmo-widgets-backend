package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.LinkAudience
import dev.alllexey.itmowidgets.backend.dto.SubjectLink
import dev.alllexey.itmowidgets.backend.dto.SubjectLinkRevision
import dev.alllexey.itmowidgets.backend.dto.SubjectLinkStatus
import dev.alllexey.itmowidgets.backend.dto.UserData
import dev.alllexey.itmowidgets.backend.model.LinkRevisionStatus
import dev.alllexey.itmowidgets.backend.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.model.SubjectLinkEntity
import dev.alllexey.itmowidgets.backend.model.SubjectLinkRevisionEntity
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.ModerationReportRepository
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkRevisionRepository
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkVoteRepository
import org.springframework.stereotype.Component
import java.util.UUID

/** A link a viewer may see and the approved revision that supplies its content and audience. */
data class ShownLink(val link: SubjectLinkEntity, val revision: SubjectLinkRevisionEntity)

/** Viewer-scoped visibility and DTOs of subject links. Callers hold the transaction. */
@Component
class SubjectLinkViews(
    private val revisions: SubjectLinkRevisionRepository,
    private val votes: SubjectLinkVoteRepository,
    private val reports: ModerationReportRepository,
    private val flows: FlowMembership,
    private val privacy: UserPrivacyService,
) {
    /**
     * The candidates [viewerId] may see as published content: not hidden, not currently private,
     * with an approved revision whose audience includes the viewer. The approved revision's
     * visibility and flow decide the audience, so a pending widening to ALL never leaks early.
     */
    fun shown(viewerId: UUID, candidates: List<SubjectLinkEntity>): List<ShownLink> {
        val open = candidates.filter { it.hiddenAt == null && it.visibility != LinkVisibility.PRIVATE }
        if (open.isEmpty()) return emptyList()
        val approved = revisions.findLatestApprovedIn(open.map { it.id }).associateBy { it.link.id }
        val membership = HashMap<Triple<Long, String, Long>, Boolean>()
        return open.mapNotNull { link ->
            val revision = approved[link.id] ?: return@mapNotNull null
            val visible = link.owner.id == viewerId || when (revision.visibility) {
                LinkVisibility.ALL -> true
                LinkVisibility.FLOW -> revision.flowId?.let { flowId ->
                    membership.getOrPut(Triple(link.subjectId, link.periodKey, flowId)) {
                        flows.isMember(viewerId, link.subjectId, link.periodKey, flowId)
                    }
                } == true
                LinkVisibility.PRIVATE -> false
            }
            if (visible) ShownLink(link, revision) else null
        }
    }

    fun shown(viewerId: UUID, link: SubjectLinkEntity): ShownLink? = shown(viewerId, listOf(link)).firstOrNull()

    /** The owner's own links with their current content and review state. */
    fun owned(viewer: User, links: List<SubjectLinkEntity>): List<SubjectLink> {
        if (links.isEmpty()) return emptyList()
        val ids = links.map { it.id }
        val latest = revisions.findLatestIn(ids).associateBy { it.link.id }
        val labels = Labels()
        return links.map { link ->
            val status = ownerStatus(link, latest[link.id])
            SubjectLink(link.id, link.subjectId, link.subjectName, link.periodKey, link.category, link.url, link.title,
                link.visibility, link.flowId, labels.of(link, link.flowId), status,
                reviewNote = latest[link.id]?.note?.takeIf { status == SubjectLinkStatus.REJECTED },
                score = link.score, myVote = 0, isMine = true, reportedByMe = false, author = null,
                updatedAt = link.updatedAt)
        }
    }

    /** Published content as another viewer sees it; the viewer's own previous-period links stay authorless. */
    fun published(viewer: User, shown: List<ShownLink>): List<SubjectLink> {
        if (shown.isEmpty()) return emptyList()
        val ids = shown.map { it.link.id }
        val myVotes = votes.findByUserAndLinks(viewer.id, ids).associate { it.id.linkId to it.value.toInt() }
        val reported = reports.findReportedTargetIds(TYPE, shown.map { it.revision.id }, viewer.id).toSet()
        val labels = Labels()
        val authors = HashMap<UUID, UserData>()
        return shown.map { (link, revision) ->
            val mine = link.owner.id == viewer.id
            SubjectLink(link.id, link.subjectId, link.subjectName, link.periodKey, revision.category, revision.url,
                revision.title, revision.visibility, revision.flowId, labels.of(link, revision.flowId), SubjectLinkStatus.PUBLISHED,
                reviewNote = null, score = link.score, myVote = myVotes[link.id] ?: 0, isMine = mine,
                reportedByMe = revision.id in reported,
                author = if (mine) null else authors.getOrPut(link.owner.id) { privacy.userDataFor(viewer, link.owner) },
                updatedAt = revision.decidedAt ?: revision.submittedAt)
        }
    }

    fun published(viewer: User, shown: ShownLink): SubjectLink = published(viewer, listOf(shown)).single()

    /**
     * What a moderator needs next to a reviewed revision: the content others currently see (or the
     * reviewed content before the first approval) with the owner-side status.
     */
    fun moderated(viewer: User, link: SubjectLinkEntity, reviewed: SubjectLinkRevisionEntity): SubjectLink {
        val content = revisions.findLatestApproved(link.id) ?: reviewed
        val latest = revisions.findLatest(link.id)
        val status = ownerStatus(link, latest)
        val mine = link.owner.id == viewer.id
        return SubjectLink(link.id, link.subjectId, link.subjectName, link.periodKey, content.category, content.url,
            content.title, content.visibility, content.flowId, Labels().of(link, content.flowId),
            status, reviewNote = latest?.note?.takeIf { status == SubjectLinkStatus.REJECTED }, score = link.score,
            myVote = votes.findByUserAndLinks(viewer.id, listOf(link.id)).firstOrNull()?.value?.toInt() ?: 0,
            isMine = mine,
            reportedByMe = reports.existsByTargetAndReporter(TYPE, content.id, viewer.id),
            author = if (mine) null else privacy.userDataFor(viewer, link.owner),
            updatedAt = content.decidedAt ?: content.submittedAt)
    }

    fun author(viewer: User, owner: User): UserData = privacy.userDataFor(viewer, owner)

    /** Every flow of the viewer in the subject and period: shallower flows first, then by name. */
    fun audiences(viewerId: UUID, subjectId: Long, periodKey: String): List<LinkAudience> =
        flows.flowsOf(viewerId, subjectId, periodKey)
            .sortedWith(compareBy<SubjectFlow> { it.depth }.thenBy { it.groupName }.thenBy { it.flowId })
            .map { LinkAudience(it.flowId, it.groupName, it.typeId, it.depth) }

    fun revision(revision: SubjectLinkRevisionEntity) = SubjectLinkRevision(revision.id, revision.link.id, revision.number,
        revision.category, revision.url, revision.title, revision.visibility, revision.flowId, revision.status,
        revision.submittedAt, revision.decidedAt, revision.note)

    /** A pending revision is always the newest one: a new revision withdraws the previous pending one. */
    private fun ownerStatus(link: SubjectLinkEntity, latest: SubjectLinkRevisionEntity?): SubjectLinkStatus = when {
        link.hiddenAt != null -> SubjectLinkStatus.HIDDEN
        link.visibility == LinkVisibility.PRIVATE || latest == null -> SubjectLinkStatus.PRIVATE
        latest.status == LinkRevisionStatus.PENDING -> SubjectLinkStatus.PENDING
        latest.status == LinkRevisionStatus.REJECTED -> SubjectLinkStatus.REJECTED
        else -> SubjectLinkStatus.PUBLISHED
    }

    /** A FLOW link is labelled with the schedule name of its flow as the author's schedule has it. */
    private inner class Labels {
        private val cache = HashMap<Triple<UUID, Long, String>, List<SubjectFlow>>()

        fun of(link: SubjectLinkEntity, flowId: Long?): String? {
            if (flowId == null) return null
            val authorFlows = cache.getOrPut(Triple(link.owner.id, link.subjectId, link.periodKey)) {
                flows.flowsOf(link.owner.id, link.subjectId, link.periodKey)
            }
            return authorFlows.firstOrNull { it.flowId == flowId }?.groupName
        }
    }

    private companion object {
        val TYPE = ModerationTargetType.SUBJECT_RESOURCE
    }
}
