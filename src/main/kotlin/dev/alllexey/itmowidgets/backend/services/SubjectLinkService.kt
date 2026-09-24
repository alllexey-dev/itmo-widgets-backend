package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.ModerationCaseTarget
import dev.alllexey.itmowidgets.backend.dto.ModerationReportRequest
import dev.alllexey.itmowidgets.backend.dto.PinSubjectLinkRequest
import dev.alllexey.itmowidgets.backend.dto.SaveSubjectLinkRequest
import dev.alllexey.itmowidgets.backend.dto.SubjectLink
import dev.alllexey.itmowidgets.backend.dto.SubjectLinkTarget
import dev.alllexey.itmowidgets.backend.dto.SubjectLinksResponse
import dev.alllexey.itmowidgets.backend.dto.SubmitterHistory
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.model.LinkCategory
import dev.alllexey.itmowidgets.backend.model.LinkRevisionStatus
import dev.alllexey.itmowidgets.backend.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.model.ModerationAction
import dev.alllexey.itmowidgets.backend.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.model.ResourceUrlPolicy
import dev.alllexey.itmowidgets.backend.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.model.SubjectLinkEntity
import dev.alllexey.itmowidgets.backend.model.SubjectLinkPinEntity
import dev.alllexey.itmowidgets.backend.model.SubjectLinkPinId
import dev.alllexey.itmowidgets.backend.model.SubjectLinkRevisionEntity
import dev.alllexey.itmowidgets.backend.model.SubjectLinkSaveEntity
import dev.alllexey.itmowidgets.backend.model.SubjectLinkSaveId
import dev.alllexey.itmowidgets.backend.model.SubjectLinkVoteEntity
import dev.alllexey.itmowidgets.backend.model.SubjectLinkVoteId
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.ModerationReportRepository
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkPinRepository
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkRepository
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkRevisionRepository
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkSaveRepository
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkVoteRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Subject links of one subject and period. The link row holds the owner's current content;
 * every non-private change becomes an immutable revision, and other viewers see the latest
 * approved revision. FLOW revisions and ALL without premoderation are approved by
 * the policy at once; ALL with premoderation waits for a moderator. Case targets are revisions.
 */
@Service
class SubjectLinkService(
    private val links: SubjectLinkRepository,
    private val revisions: SubjectLinkRevisionRepository,
    private val votes: SubjectLinkVoteRepository,
    private val saves: SubjectLinkSaveRepository,
    private val pins: SubjectLinkPinRepository,
    private val users: UserRepository,
    private val views: SubjectLinkViews,
    private val flows: FlowMembership,
    private val restrictions: RestrictionService,
    private val settings: ModerationSettingsService,
    private val moderation: ModerationService,
    private val reports: ModerationReportService,
    private val reportRows: ModerationReportRepository,
    private val clock: Clock,
) : ModerationTarget {

    @Transactional(readOnly = true)
    fun links(viewerId: UUID, subjectId: Long, periodKey: String): SubjectLinksResponse {
        requireScope(subjectId, periodKey)
        val viewer = user(viewerId)
        val mine = views.owned(viewer, links.findOwned(viewerId, subjectId, periodKey))
        val others = links.findVisibleCandidates(subjectId, periodKey).filter { it.owner.id != viewerId }
        val shared = views.shown(viewerId, others).collapseDuplicates()
            .sortedWith(compareBy<ShownLink> { it.revision.visibility == LinkVisibility.ALL }
                .thenByDescending { it.link.score }.thenBy { it.link.createdAt }.thenBy { it.link.id })
        val previous = views.shown(viewerId, links.findPrevious(subjectId, periodKey, LinkCategory.PREVIOUS_YEARS, Limit.of(PREVIOUS_LIMIT)))
            .filter { it.revision.visibility == LinkVisibility.ALL && it.revision.category in LinkCategory.PREVIOUS_YEARS }
            .collapseDuplicates()
        val listed = mine.map { it.id } + shared.map { it.link.id } + previous.map { it.link.id }
        val pinned = pins.findById(SubjectLinkPinId(viewerId, subjectId, periodKey)).orElse(null)?.linkId?.takeIf { it in listed }
        return SubjectLinksResponse(mine, views.published(viewer, shared), views.published(viewer, previous), pinned,
            views.audiences(viewerId, subjectId, periodKey), settings.policy(TYPE).premoderation)
    }

    /** Creates the link under the client's UUID or edits the caller's own link. */
    @Transactional
    fun save(viewerId: UUID, id: UUID, request: SaveSubjectLinkRequest): SubjectLink {
        val content = request.content()
        val subjectName = request.subjectName.trim()
        if (subjectName.isEmpty() || subjectName.length > 200) throw InvalidRequestDataException("Invalid subject name")
        moderation.lock(TYPE)
        users.lockById(viewerId) ?: throw NotFoundException("User not found")
        links.lockById(id)
        val existing = links.findById(id).orElse(null)
        if (existing != null && existing.owner.id != viewerId) throw PermissionDeniedException("Only the owner can change this link")
        if (existing != null && (existing.subjectId != request.subjectId || existing.periodKey != request.periodKey)) {
            throw InvalidRequestDataException("The subject and period of a link cannot change")
        }
        if (content.visibility != LinkVisibility.PRIVATE) restrictions.require(viewerId, RestrictionCapability.SUBMIT_RESOURCES)
        requireAudience(viewerId, request.subjectId, request.periodKey, content)
        val changed = existing == null || existing.content() != content
        val now = clock.instant()
        if (changed && content.visibility != LinkVisibility.PRIVATE &&
            revisions.countByOwnerSince(viewerId, now.minusSeconds(DAY_SECONDS)) >= settings.policy(TYPE).dailySubmissionLimit) {
            throw BusinessRuleException("Daily submission limit reached")
        }
        val viewer = user(viewerId)
        val link = links.save((existing ?: SubjectLinkEntity(id = id, owner = viewer, subjectId = request.subjectId,
            subjectName = subjectName, periodKey = request.periodKey, category = content.category, url = content.url,
            normalizedUrl = content.normalizedUrl, title = content.title, visibility = content.visibility,
            flowId = content.flowId, createdAt = now, updatedAt = now)).also {
            if (changed || it.subjectName != subjectName) it.updatedAt = now
            it.subjectName = subjectName
            it.replaceContent(content)
        })
        if (content.visibility == LinkVisibility.PRIVATE) withdrawPending(link, now)
        else if (changed) submit(link, content, now)
        return views.owned(viewer, listOf(link)).single()
    }

    /** Deletes the caller's own link; open cases of its revisions are withdrawn and their reports removed. */
    @Transactional
    fun delete(viewerId: UUID, id: UUID) {
        moderation.lock(TYPE)
        links.lockById(id) ?: return
        val link = links.findById(id).orElseThrow { NotFoundException("Link not found") }
        if (link.owner.id != viewerId) throw PermissionDeniedException("Only the owner can delete this link")
        val history = revisions.findAllByLink(id)
        for (revision in history) {
            moderation.withdraw(TYPE, revision.id)
            reports.deleteAllFor(TYPE, revision.id)
        }
        // Votes, saves and pins cascade in the database; managed revisions must not outlive their link.
        revisions.deleteAll(history)
        links.delete(link)
    }

    /** Adds another student's visible link to the caller's list or removes it. */
    @Transactional
    fun setSaved(viewerId: UUID, id: UUID, saved: Boolean): SubjectLink {
        val (viewer, shown) = othersLink(viewerId, id)
        users.lockById(viewerId) ?: throw NotFoundException("User not found")
        val key = SubjectLinkSaveId(viewerId, id)
        if (saved && !saves.existsById(key)) saves.save(SubjectLinkSaveEntity(key, clock.instant()))
        if (!saved) saves.deleteById(key)
        return views.published(viewer, shown)
    }

    /** Pins one of the links the caller sees for the subject and period, or clears the pin. */
    @Transactional
    fun pin(viewerId: UUID, subjectId: Long, request: PinSubjectLinkRequest): SubjectLinksResponse {
        val current = links(viewerId, subjectId, request.periodKey)
        val key = SubjectLinkPinId(viewerId, subjectId, request.periodKey)
        val linkId = request.linkId
        if (linkId == null) {
            pins.deleteById(key)
            return current.copy(pinnedId = null)
        }
        if ((current.mine + current.shared + current.previous).none { it.id == linkId }) throw NotFoundException("Link not found")
        users.lockById(viewerId) ?: throw NotFoundException("User not found")
        pins.save(pins.findById(key).orElse(null)?.also { it.linkId = linkId } ?: SubjectLinkPinEntity(key, linkId))
        return current.copy(pinnedId = linkId)
    }

    /** -1 or 1 replaces the caller's vote, 0 removes it. A low score opens a VOTES case on the shown revision. */
    @Transactional
    fun vote(viewerId: UUID, id: UUID, value: Int): SubjectLink {
        if (value !in -1..1) throw InvalidRequestDataException("Vote must be -1, 0 or 1")
        restrictions.require(viewerId, RestrictionCapability.VOTE)
        moderation.lock(TYPE)
        links.lockById(id) ?: throw NotFoundException("Link not found")
        val (viewer, shown) = othersLink(viewerId, id)
        val key = SubjectLinkVoteId(id, viewerId)
        val existing = votes.findById(key).orElse(null)
        when {
            value == 0 -> existing?.let(votes::delete)
            existing != null -> existing.value = value.toShort()
            else -> votes.save(SubjectLinkVoteEntity(key, value.toShort(), clock.instant()))
        }
        votes.flush()
        val link = shown.link.also { it.score = votes.sumValues(id) }
        links.save(link)
        if (link.score <= settings.policy(TYPE).voteThreshold) moderation.openCase(TYPE, shown.revision.id, ModerationCaseReason.VOTES)
        return views.published(viewer, shown)
    }

    /** Reports the revision the caller currently sees. */
    @Transactional
    fun report(viewerId: UUID, id: UUID, request: ModerationReportRequest): SubjectLink {
        val (viewer, shown) = othersLink(viewerId, id)
        reports.report(viewerId, TYPE, shown.revision.id, request)
        return views.published(viewer, shown)
    }

    override fun targetType() = TYPE

    override fun ownerId(targetId: UUID): UUID = revision(targetId).link.owner.id

    override fun isReportable(targetId: UUID, reporterId: UUID): Boolean {
        val revision = revisions.findById(targetId).orElse(null) ?: return false
        return revision.link.owner.id != reporterId && views.shown(reporterId, revision.link)?.revision?.id == revision.id
    }

    /** A hidden link keeps its pending revision for a moderator even when premoderation is switched off. */
    override fun canAutoApprove(targetId: UUID): Boolean = revisions.findById(targetId).orElse(null)
        ?.let { it.status == LinkRevisionStatus.PENDING && it.link.hiddenAt == null } == true

    /**
     * APPROVE publishes a pending revision (an approved one stays as is). REJECT declines a pending
     * revision or withdraws an approved one, so others fall back to the previous approved content.
     */
    override fun apply(action: ModerationAction, targetId: UUID, decision: ModerationDecisionEntity) {
        val revision = revision(targetId)
        when (action) {
            ModerationAction.APPROVE -> when (revision.status) {
                LinkRevisionStatus.PENDING -> decide(revision, LinkRevisionStatus.APPROVED, decision)
                LinkRevisionStatus.APPROVED -> Unit
                else -> throw BusinessRuleException("Only a pending revision can be approved")
            }
            ModerationAction.REJECT -> when (revision.status) {
                LinkRevisionStatus.PENDING, LinkRevisionStatus.APPROVED -> decide(revision, LinkRevisionStatus.REJECTED, decision)
                else -> throw BusinessRuleException("Only a pending or approved revision can be rejected")
            }
            ModerationAction.HIDE -> revision.link.hiddenAt = revision.link.hiddenAt ?: decision.createdAt
            ModerationAction.RESTORE -> revision.link.hiddenAt = null
            ModerationAction.DISMISS -> reports.dismissAll(TYPE, targetId)
            ModerationAction.RESTRICT_USER -> Unit
            ModerationAction.HIDE_ALL_BY_USER -> hideAllBy(revision.link.owner.id, targetId, decision)
        }
        links.save(revision.link)
    }

    override fun describe(targetId: UUID, viewerId: UUID): ModerationCaseTarget {
        val revision = revision(targetId)
        val link = revision.link
        val owner = link.owner
        val viewer = user(viewerId)
        val history = SubmitterHistory(
            approved = revisions.countByOwnerAndStatus(owner.id, LinkRevisionStatus.APPROVED),
            rejected = revisions.countByOwnerAndStatus(owner.id, LinkRevisionStatus.REJECTED),
            dismissedReports = reportRows.countByReporterIdAndDismissedAtIsNotNull(owner.id),
            activeRestrictions = restrictions.activeFor(owner.id),
        )
        return SubjectLinkTarget(views.revision(revision), views.moderated(viewer, link, revision),
            views.author(viewer, owner),
            reports.activeFor(TYPE, targetId), history)
    }

    private fun submit(link: SubjectLinkEntity, content: LinkContent, now: Instant) {
        withdrawPending(link, now)
        val automatic = content.visibility != LinkVisibility.ALL || !settings.policy(TYPE).premoderation
        val revision = revisions.save(SubjectLinkRevisionEntity(link = link,
            number = (revisions.findLatest(link.id)?.number ?: 0) + 1, category = content.category, url = content.url,
            normalizedUrl = content.normalizedUrl, title = content.title, visibility = content.visibility,
            flowId = content.flowId, status = if (automatic) LinkRevisionStatus.APPROVED else LinkRevisionStatus.PENDING,
            submittedAt = now, decidedAt = if (automatic) now else null))
        if (automatic) moderation.approveByPolicy(TYPE, revision.id)
        else moderation.openCase(TYPE, revision.id, ModerationCaseReason.SUBMISSION)
    }

    private fun withdrawPending(link: SubjectLinkEntity, now: Instant) {
        val pending = revisions.findPending(link.id) ?: return
        pending.status = LinkRevisionStatus.WITHDRAWN
        pending.decidedAt = now
        // Flush before a new PENDING insert: Hibernate runs inserts ahead of updates.
        revisions.saveAndFlush(pending)
        moderation.withdraw(TYPE, pending.id)
    }

    private fun decide(revision: SubjectLinkRevisionEntity, status: LinkRevisionStatus, decision: ModerationDecisionEntity) {
        revision.status = status
        revision.decidedAt = decision.createdAt
        revision.note = decision.note
        revisions.save(revision)
    }

    /** Hides every published link of the author and rejects their pending revisions; private drafts stay. */
    private fun hideAllBy(ownerId: UUID, initiatingTarget: UUID, decision: ModerationDecisionEntity) {
        val visible = links.findAllByOwner(ownerId).filter { it.hiddenAt == null }
        if (visible.isNotEmpty()) {
            val published = revisions.findLatestApprovedIn(visible.map { it.id }).map { it.link.id }.toSet()
            visible.filter { it.id in published }.forEach { it.hiddenAt = decision.createdAt }
        }
        for (pending in revisions.findPendingByOwner(ownerId)) {
            decide(pending, LinkRevisionStatus.REJECTED, decision)
            // The initiating case stays open for the moderator's next decision.
            if (pending.id != initiatingTarget) moderation.withdraw(TYPE, pending.id)
        }
    }

    /** Another owner's link the caller currently sees; own and invisible links are refused. */
    private fun othersLink(viewerId: UUID, id: UUID): Pair<User, ShownLink> {
        val link = links.findById(id).orElseThrow { NotFoundException("Link not found") }
        if (link.owner.id == viewerId) throw BusinessRuleException("Not allowed on own links")
        val shown = views.shown(viewerId, link) ?: throw NotFoundException("Link not found")
        return user(viewerId) to shown
    }

    /** A FLOW link goes to one of the author's own flows of the subject and period. */
    private fun requireAudience(userId: UUID, subjectId: Long, periodKey: String, content: LinkContent) {
        val flowId = content.flowId ?: return
        if (!flows.isMember(userId, subjectId, periodKey, flowId)) throw InvalidRequestDataException("audience_unavailable")
    }

    private fun revision(id: UUID): SubjectLinkRevisionEntity =
        revisions.findById(id).orElseThrow { NotFoundException("Link revision not found") }

    private fun user(id: UUID): User = users.findById(id).orElseThrow { NotFoundException("User not found") }

    private data class LinkContent(
        val category: LinkCategory,
        val url: String,
        val normalizedUrl: String,
        val title: String?,
        val visibility: LinkVisibility,
        val flowId: Long?,
    )

    private fun SaveSubjectLinkRequest.content(): LinkContent {
        requireScope(subjectId, periodKey)
        val trimmedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedTitle != null && trimmedTitle.length > 120) throw InvalidRequestDataException("Title is too long")
        if ((visibility == LinkVisibility.FLOW) != (flowId != null)) {
            throw InvalidRequestDataException("A flow is required with FLOW visibility and only with it")
        }
        val normalized = ResourceUrlPolicy.normalize(url)
        return LinkContent(category, normalized.url, normalized.normalizedUrl, trimmedTitle, visibility, flowId)
    }

    private fun SubjectLinkEntity.content() = LinkContent(category, url, normalizedUrl, title, visibility, flowId)

    private fun SubjectLinkEntity.replaceContent(content: LinkContent) {
        category = content.category
        url = content.url
        normalizedUrl = content.normalizedUrl
        title = content.title
        visibility = content.visibility
        flowId = content.flowId
    }

    /** Links with the same normalized URL show once: the highest score wins, earlier links break ties. */
    private fun List<ShownLink>.collapseDuplicates(): List<ShownLink> = groupBy { it.revision.normalizedUrl }.values
        .map { same -> same.sortedWith(compareByDescending<ShownLink> { it.link.score }.thenBy { it.link.createdAt }).first() }
        .let { kept -> filter { it in kept } }

    private fun requireScope(subjectId: Long, periodKey: String) {
        if (subjectId <= 0 || !PERIOD_KEY.matches(periodKey)) throw InvalidRequestDataException("Invalid subject or period")
    }

    private companion object {
        val TYPE = ModerationTargetType.SUBJECT_RESOURCE
        val PERIOD_KEY = Regex("^[0-9]{4}-[12]$")
        const val PREVIOUS_LIMIT = 10
        const val DAY_SECONDS = 86_400L
    }
}
