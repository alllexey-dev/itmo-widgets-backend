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
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkAudienceRepository
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkRevisionRepository
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkSaveRepository
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkVoteRepository
import org.springframework.stereotype.Component
import java.util.UUID

/** A link a viewer may see and the approved revision that supplies its content. */
data class ShownLink(val link: SubjectLinkEntity, val revision: SubjectLinkRevisionEntity, val audience: Set<Long>)

/** Viewer-scoped visibility and DTOs of subject links. Callers hold the transaction. */
@Component
class SubjectLinkViews(
    private val revisions: SubjectLinkRevisionRepository,
    private val audiences: SubjectLinkAudienceRepository,
    private val votes: SubjectLinkVoteRepository,
    private val saves: SubjectLinkSaveRepository,
    private val reports: ModerationReportRepository,
    private val flows: FlowMembership,
    private val privacy: UserPrivacyService,
) {
    /**
     * The candidates [viewerId] may see as published content: not hidden, not currently private,
     * with an approved revision whose audience includes the viewer. The approved revision's
     * visibility decides the audience, so a pending widening to ALL never leaks early.
     */
    fun shown(viewerId: UUID, candidates: List<SubjectLinkEntity>): List<ShownLink> {
        val open = candidates.filter { it.hiddenAt == null && it.visibility != LinkVisibility.PRIVATE }
        if (open.isEmpty()) return emptyList()
        val ids = open.map { it.id }
        val approved = revisions.findLatestApprovedIn(ids).associateBy { it.link.id }
        val audience = audienceOf(ids)
        val shares = HashMap<Triple<Long, String, Set<Long>>, Boolean>()
        return open.mapNotNull { link ->
            val revision = approved[link.id] ?: return@mapNotNull null
            val flowIds = audience[link.id].orEmpty()
            val visible = link.owner.id == viewerId || when (revision.visibility) {
                LinkVisibility.ALL -> true
                LinkVisibility.GROUP, LinkVisibility.FLOW -> shares.getOrPut(Triple(link.subjectId, link.periodKey, flowIds)) {
                    flows.sharesAny(viewerId, link.subjectId, link.periodKey, flowIds)
                }
                LinkVisibility.PRIVATE -> false
            }
            if (visible) ShownLink(link, revision, flowIds) else null
        }
    }

    fun shown(viewerId: UUID, link: SubjectLinkEntity): ShownLink? = shown(viewerId, listOf(link)).firstOrNull()

    /** The owner's own links with their current content and review state. */
    fun owned(viewer: User, links: List<SubjectLinkEntity>): List<SubjectLink> {
        if (links.isEmpty()) return emptyList()
        val ids = links.map { it.id }
        val latest = revisions.findLatestIn(ids).associateBy { it.link.id }
        val audience = audienceOf(ids)
        val labels = Labels()
        return links.map { link ->
            val status = ownerStatus(link, latest[link.id])
            SubjectLink(link.id, link.subjectId, link.subjectName, link.periodKey, link.category, link.url, link.title,
                link.visibility, labels.of(link, link.visibility, audience[link.id].orEmpty()), status,
                reviewNote = latest[link.id]?.note?.takeIf { status == SubjectLinkStatus.REJECTED },
                score = link.score, myVote = 0, isMine = true, isSaved = false, reportedByMe = false, author = null,
                updatedAt = link.updatedAt)
        }
    }

    /** Published content as another viewer sees it; the viewer's own previous-period links stay authorless. */
    fun published(viewer: User, shown: List<ShownLink>): List<SubjectLink> {
        if (shown.isEmpty()) return emptyList()
        val ids = shown.map { it.link.id }
        val myVotes = votes.findByUserAndLinks(viewer.id, ids).associate { it.id.linkId to it.value.toInt() }
        val saved = saves.findSavedLinkIds(viewer.id, ids).toSet()
        val reported = reports.findReportedTargetIds(TYPE, shown.map { it.revision.id }, viewer.id).toSet()
        val labels = Labels()
        val authors = HashMap<UUID, UserData>()
        return shown.map { (link, revision, audience) ->
            val mine = link.owner.id == viewer.id
            SubjectLink(link.id, link.subjectId, link.subjectName, link.periodKey, revision.category, revision.url,
                revision.title, revision.visibility, labels.of(link, revision.visibility, audience), SubjectLinkStatus.PUBLISHED,
                reviewNote = null, score = link.score, myVote = myVotes[link.id] ?: 0, isMine = mine,
                isSaved = link.id in saved, reportedByMe = revision.id in reported,
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
            content.title, content.visibility, Labels().of(link, content.visibility, audienceOf(listOf(link.id))[link.id].orEmpty()),
            status, reviewNote = latest?.note?.takeIf { status == SubjectLinkStatus.REJECTED }, score = link.score,
            myVote = votes.findByUserAndLinks(viewer.id, listOf(link.id)).firstOrNull()?.value?.toInt() ?: 0,
            isMine = mine, isSaved = saves.findSavedLinkIds(viewer.id, listOf(link.id)).isNotEmpty(),
            reportedByMe = reports.existsByTargetAndReporter(TYPE, content.id, viewer.id),
            author = if (mine) null else privacy.userDataFor(viewer, link.owner),
            updatedAt = content.decidedAt ?: content.submittedAt)
    }

    fun author(viewer: User, owner: User): UserData = privacy.userDataFor(viewer, owner)

    /** GROUP is every non-lecture flow of the viewer in the subject and period, FLOW every lecture flow. */
    fun audiences(viewerId: UUID, subjectId: Long, periodKey: String): List<LinkAudience> {
        val own = flows.flowsOf(viewerId, subjectId, periodKey)
        return listOf(LinkVisibility.GROUP to own.filterNot { it.lecture }, LinkVisibility.FLOW to own.filter { it.lecture })
            .filter { (_, flows) -> flows.isNotEmpty() }
            .map { (visibility, flows) -> LinkAudience(visibility, label(flows.map { it.groupName }).orEmpty()) }
    }

    fun revision(revision: SubjectLinkRevisionEntity) = SubjectLinkRevision(revision.id, revision.link.id, revision.number,
        revision.category, revision.url, revision.title, revision.visibility, revision.status, revision.submittedAt,
        revision.decidedAt, revision.note)

    private fun audienceOf(linkIds: List<UUID>): Map<UUID, Set<Long>> =
        audiences.findAllByLinkIds(linkIds).groupBy({ it.id.linkId }, { it.id.flowId }).mapValues { it.value.toSet() }

    /** A pending revision is always the newest one: a new revision withdraws the previous pending one. */
    private fun ownerStatus(link: SubjectLinkEntity, latest: SubjectLinkRevisionEntity?): SubjectLinkStatus = when {
        link.hiddenAt != null -> SubjectLinkStatus.HIDDEN
        link.visibility == LinkVisibility.PRIVATE || latest == null -> SubjectLinkStatus.PRIVATE
        latest.status == LinkRevisionStatus.PENDING -> SubjectLinkStatus.PENDING
        latest.status == LinkRevisionStatus.REJECTED -> SubjectLinkStatus.REJECTED
        else -> SubjectLinkStatus.PUBLISHED
    }

    /** Audience labels come from the author's flows the link was published to. */
    private inner class Labels {
        private val cache = HashMap<Triple<UUID, Long, String>, List<SubjectFlow>>()

        fun of(link: SubjectLinkEntity, visibility: LinkVisibility, audience: Set<Long>): String? {
            if (visibility != LinkVisibility.GROUP && visibility != LinkVisibility.FLOW) return null
            val authorFlows = cache.getOrPut(Triple(link.owner.id, link.subjectId, link.periodKey)) {
                flows.flowsOf(link.owner.id, link.subjectId, link.periodKey)
            }
            return label(authorFlows.filter { it.flowId in audience }.map { it.groupName })
        }
    }

    /** Lecture flows name several groups (`P3119, P3120`); every group is listed once. */
    private fun label(groupNames: List<String>): String? = groupNames.flatMap { it.split(',') }.map(String::trim)
        .filter(String::isNotEmpty).distinct().joinToString(", ").ifEmpty { null }

    private companion object {
        val TYPE = ModerationTargetType.SUBJECT_RESOURCE
    }
}
