package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.LinkAudience
import dev.alllexey.itmowidgets.backend.dto.ModerationDecisionRequest
import dev.alllexey.itmowidgets.backend.dto.ModerationReportRequest
import dev.alllexey.itmowidgets.backend.dto.ModerationSettings
import dev.alllexey.itmowidgets.backend.dto.PinSubjectLinkRequest
import dev.alllexey.itmowidgets.backend.dto.SaveSubjectLinkRequest
import dev.alllexey.itmowidgets.backend.dto.SubjectLink
import dev.alllexey.itmowidgets.backend.dto.SubjectLinkStatus
import dev.alllexey.itmowidgets.backend.dto.SubjectLinkTarget
import dev.alllexey.itmowidgets.backend.dto.SubjectLinksResponse
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.exceptions.RestrictedException
import dev.alllexey.itmowidgets.backend.model.*
import dev.alllexey.itmowidgets.backend.repositories.ModerationCaseRepository
import dev.alllexey.itmowidgets.backend.repositories.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkRepository
import dev.alllexey.itmowidgets.backend.repositories.SubjectLinkRevisionRepository
import dev.alllexey.itmowidgets.backend.repositories.UserSubjectFlowRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean

@Import(SubjectLinkService::class, SubjectLinkViews::class, ScheduleFlowMembership::class, UserPrivacyService::class,
    RestrictionService::class, ModerationSettingsService::class, ModerationService::class, ModerationReportService::class,
    ModerationTargets::class, ModeratorAccess::class, SubjectLinkServiceTest.TimeConfig::class)
class SubjectLinkServiceTest @Autowired constructor(
    private val service: SubjectLinkService,
    private val moderation: ModerationService,
    private val settings: ModerationSettingsService,
    private val flows: UserSubjectFlowRepository,
    private val links: SubjectLinkRepository,
    private val revisions: SubjectLinkRevisionRepository,
    private val cases: ModerationCaseRepository,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var friends: FriendService

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig { @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC) }

    private var nextIsu = 962000
    private lateinit var moderator: User

    @BeforeEach
    fun moderator() {
        moderator = user()
        em.persistAndFlush(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), NOW))
    }

    @Test
    fun `a private link is visible to nobody but its owner and creates no revision`() {
        val owner = user().practice(7002)
        val classmate = user().practice(7002)
        val id = save(owner, LinkVisibility.PRIVATE).id

        assertEquals(SubjectLinkStatus.PRIVATE, links(owner).mine.single().status)
        assertNull(revisions.findLatest(id))
        assertTrue(links(classmate).shared.isEmpty())
        assertFailsWith<NotFoundException> { service.setSaved(classmate.id, id, true) }
        assertFailsWith<NotFoundException> { service.vote(classmate.id, id, 1) }
        assertFailsWith<NotFoundException> { service.pin(classmate.id, SUBJECT, PinSubjectLinkRequest(PERIOD, id)) }
    }

    @Test
    fun `a group link reaches the author's practice flow at once but not another intake with the same group name`() {
        val author = user().practice(7002).lecture(7001)
        val classmate = user().practice(7002)
        val lectureOnly = user().lecture(7001, "P3119, P3120")
        val otherIntake = user().practice(9002)
        val saved = save(author, LinkVisibility.GROUP)

        assertEquals(SubjectLinkStatus.PUBLISHED, saved.status)
        assertEquals("P3119", saved.audienceLabel)
        val seen = links(classmate).shared.single()
        assertEquals(saved.id, seen.id)
        assertEquals("P3119", seen.audienceLabel)
        assertEquals(author.isu, seen.author?.isu)
        assertFalse(seen.isMine)
        assertTrue(links(lectureOnly).shared.isEmpty())
        assertTrue(links(otherIntake).shared.isEmpty())
        val decision = policyDecision(revisions.findLatest(saved.id)!!.id)
        assertEquals(ModerationActor.POLICY, decision.actor)
        assertNull(decision.moderator)
        assertEquals(ModerationCaseStatus.RESOLVED, decision.case.status)
    }

    @Test
    fun `a flow link reaches the author's lecture flow and audiences name each group once`() {
        val author = user().practice(7002).lecture(7001, "P3119, P3120").lecture(7003, "P3119, P3121")
        val practiceOnly = user().practice(7002)
        val lectureMate = user().lecture(7001, "P3119, P3120")
        val saved = save(author, LinkVisibility.FLOW)

        assertEquals("P3119, P3120, P3121", saved.audienceLabel)
        assertEquals(listOf(saved.id), links(lectureMate).shared.map { it.id })
        assertTrue(links(practiceOnly).shared.isEmpty())
        assertEquals(listOf(LinkAudience(LinkVisibility.GROUP, "P3119"), LinkAudience(LinkVisibility.FLOW, "P3119, P3120, P3121")),
            links(author).audiences)
        assertTrue(links(user()).audiences.isEmpty())
    }

    @Test
    fun `a public link waits for approval under premoderation and is published at once without it`() {
        val author = user()
        val reader = user()
        val pending = save(author, LinkVisibility.ALL, url = "https://example.org/reviewed")

        assertEquals(SubjectLinkStatus.PENDING, pending.status)
        assertTrue(links(reader).shared.isEmpty())
        assertTrue(links(reader).premoderation)
        decide(pending.id, ModerationAction.APPROVE)
        assertEquals(listOf("https://example.org/reviewed"), links(reader).shared.map { it.url })
        assertEquals(SubjectLinkStatus.PUBLISHED, links(author).mine.single().status)

        premoderation(false)
        val instant = save(author, LinkVisibility.ALL, url = "https://example.org/instant")
        assertEquals(SubjectLinkStatus.PUBLISHED, instant.status)
        assertFalse(links(reader).premoderation)
        assertEquals(setOf("https://example.org/reviewed", "https://example.org/instant"), links(reader).shared.map { it.url }.toSet())
    }

    @Test
    fun `editing an approved public link keeps the old content for others until a decision`() {
        val author = user()
        val reader = user()
        val id = save(author, LinkVisibility.ALL, url = "https://example.org/first").id
        decide(id, ModerationAction.APPROVE)

        val edited = save(author, LinkVisibility.ALL, url = "https://example.org/second", title = "Новое", id = id)
        assertEquals(SubjectLinkStatus.PENDING, edited.status)
        assertEquals("https://example.org/second", edited.url)
        assertEquals("https://example.org/first", links(reader).shared.single().url)
        assertNull(links(reader).shared.single().title)
        decide(id, ModerationAction.APPROVE)
        assertEquals("Новое", links(reader).shared.single().title)

        save(author, LinkVisibility.ALL, url = "https://example.org/third", id = id)
        decide(id, ModerationAction.REJECT, note = "Не тот предмет")
        val rejected = links(author).mine.single()
        assertEquals(SubjectLinkStatus.REJECTED, rejected.status)
        assertEquals("Не тот предмет", rejected.reviewNote)
        assertEquals("https://example.org/second", links(reader).shared.single().url)
    }

    @Test
    fun `widening a group link to everybody keeps it inside the group until approval`() {
        val author = user().practice(7002)
        val classmate = user().practice(7002)
        val stranger = user()
        val id = save(author, LinkVisibility.GROUP, url = "https://example.org/group").id
        save(author, LinkVisibility.ALL, url = "https://example.org/public", id = id)

        assertTrue(links(stranger).shared.isEmpty())
        val seen = links(classmate).shared.single()
        assertEquals("https://example.org/group", seen.url)
        assertEquals(LinkVisibility.GROUP, seen.visibility)
        decide(id, ModerationAction.APPROVE)
        assertEquals("https://example.org/public", links(stranger).shared.single().url)

        save(author, LinkVisibility.PRIVATE, url = "https://example.org/public", id = id)
        assertTrue(links(stranger).shared.isEmpty())
        assertTrue(links(classmate).shared.isEmpty())
    }

    @Test
    fun `a hidden link is visible to nobody but its owner who sees HIDDEN`() {
        premoderation(false)
        val author = user()
        val reader = user()
        val id = save(author, LinkVisibility.ALL).id
        val revision = revisions.findLatestApproved(id)!!.id
        val case = moderation.openCase(ModerationTargetType.SUBJECT_RESOURCE, revision, ModerationCaseReason.REPORTS)
        moderation.decide(moderator.id, case.id, ModerationDecisionRequest(ModerationAction.HIDE))

        assertTrue(links(reader).shared.isEmpty())
        assertEquals(SubjectLinkStatus.HIDDEN, links(author).mine.single().status)
        assertFailsWith<NotFoundException> { service.vote(reader.id, id, 1) }
        save(author, LinkVisibility.ALL, url = "https://example.org/edited", id = id)
        assertTrue(links(reader).shared.isEmpty())

        moderation.decide(moderator.id, case.id, ModerationDecisionRequest(ModerationAction.RESTORE))
        assertEquals(listOf(id), links(reader).shared.map { it.id })
    }

    @Test
    fun `duplicate URLs collapse into the higher score and group links come first`() {
        premoderation(false)
        val reader = user().practice(7002)
        val low = save(user(), LinkVisibility.ALL, url = "https://example.org/dup?utm_source=chat")
        val high = save(user(), LinkVisibility.ALL, url = "https://example.org/dup/")
        val other = save(user(), LinkVisibility.ALL, url = "https://example.org/other")
        val group = save(user().practice(7002), LinkVisibility.GROUP, url = "https://example.org/group")
        service.vote(user().id, high.id, 1)
        service.vote(user().id, other.id, 1)
        service.vote(user().id, other.id, 1)

        val shared = links(reader).shared
        assertEquals(listOf(group.id, other.id, high.id), shared.map { it.id })
        assertFalse(low.id in shared.map { it.id })
    }

    @Test
    fun `previous periods offer only approved public links of reusable categories`() {
        premoderation(false)
        val senior = user().practice(5002, period = "2025-1")
        val notes = save(senior, LinkVisibility.ALL, category = LinkCategory.NOTES, period = "2025-1", url = "https://example.org/notes")
        save(senior, LinkVisibility.ALL, category = LinkCategory.CHAT, period = "2025-1", url = "https://t.me/chat")
        save(senior, LinkVisibility.ALL, category = LinkCategory.SCORES, period = "2025-1", url = "https://example.org/scores")
        save(senior, LinkVisibility.GROUP, category = LinkCategory.EXAM, period = "2025-1", url = "https://example.org/group-exam")
        save(senior, LinkVisibility.PRIVATE, category = LinkCategory.TASKS, period = "2025-1", url = "https://example.org/private")
        save(senior, LinkVisibility.ALL, category = LinkCategory.MATERIALS, period = "2026-2", url = "https://example.org/later")

        val response = links(user())
        assertEquals(listOf(notes.id), response.previous.map { it.id })
        assertEquals(SubjectLinkStatus.PUBLISHED, response.previous.single().status)
        assertTrue(response.shared.isEmpty())
    }

    @Test
    fun `group and flow need the author's flows`() {
        val nobody = user()
        val practiceOnly = user().practice(7002)
        for ((author, visibility) in listOf(nobody to LinkVisibility.GROUP, nobody to LinkVisibility.FLOW, practiceOnly to LinkVisibility.FLOW)) {
            val error = assertFailsWith<InvalidRequestDataException> { save(author, visibility) }
            assertEquals("audience_unavailable", error.message)
        }
        assertTrue(links(nobody).mine.isEmpty())
        assertTrue(links(practiceOnly).mine.isEmpty())
    }

    @Test
    fun `invalid input and other owners' links are refused`() {
        val author = user()
        val id = save(author, LinkVisibility.PRIVATE).id
        assertFailsWith<PermissionDeniedException> { save(user(), LinkVisibility.PRIVATE, id = id) }
        assertFailsWith<InvalidRequestDataException> { save(author, LinkVisibility.PRIVATE, id = id, period = "2025-2") }
        assertFailsWith<InvalidRequestDataException> { save(author, LinkVisibility.PRIVATE, url = "http://example.org") }
        assertFailsWith<InvalidRequestDataException> { save(author, LinkVisibility.PRIVATE, title = "x".repeat(121)) }
        assertFailsWith<InvalidRequestDataException> { save(author, LinkVisibility.PRIVATE, period = "2026-3") }
        assertFailsWith<InvalidRequestDataException> { service.links(author.id, 0, PERIOD) }
        assertEquals("  Заголовок  ".trim(), save(author, LinkVisibility.PRIVATE, title = "  Заголовок  ", id = id).title)
        assertNull(save(author, LinkVisibility.PRIVATE, title = "   ", id = id).title)
    }

    @Test
    fun `restrictions block publishing voting and reporting while private links stay available`() {
        premoderation(false)
        val author = user()
        val published = save(author, LinkVisibility.ALL, url = "https://example.org/published")
        val restricted = user()
        restrict(restricted, RestrictionCapability.SUBMIT_RESOURCES)
        restrict(restricted, RestrictionCapability.VOTE)
        restrict(restricted, RestrictionCapability.REPORT)

        assertFailsWith<RestrictedException> { save(restricted, LinkVisibility.ALL) }
        val private = save(restricted, LinkVisibility.PRIVATE)
        assertFailsWith<RestrictedException> { save(restricted, LinkVisibility.ALL, id = private.id) }
        assertFailsWith<RestrictedException> { service.vote(restricted.id, published.id, 1) }
        assertFailsWith<RestrictedException> { service.report(restricted.id, published.id, ModerationReportRequest(ReportReason.SPAM)) }
        assertTrue(service.setSaved(restricted.id, published.id, true).isSaved)
    }

    @Test
    fun `the daily limit counts new revisions only`() {
        settings.update(moderator.id, ModerationSettings(mapOf(ModerationTargetType.SUBJECT_RESOURCE to
            ModerationPolicy(premoderation = false, dailySubmissionLimit = 2))))
        val author = user()
        val first = save(author, LinkVisibility.ALL, url = "https://example.org/1")
        save(author, LinkVisibility.ALL, url = "https://example.org/2")

        assertFailsWith<BusinessRuleException> { save(author, LinkVisibility.ALL, url = "https://example.org/3") }
        assertEquals(first.url, save(author, LinkVisibility.ALL, url = "https://example.org/1", id = first.id).url)
        save(author, LinkVisibility.PRIVATE, url = "https://example.org/private")
        assertEquals(2, links(author).mine.count { it.status == SubjectLinkStatus.PUBLISHED })
    }

    @Test
    fun `votes replace and remove each other and a low score opens a votes case`() {
        settings.update(moderator.id, ModerationSettings(mapOf(ModerationTargetType.SUBJECT_RESOURCE to
            ModerationPolicy(premoderation = false, voteThreshold = -2))))
        val author = user()
        val id = save(author, LinkVisibility.ALL).id
        val voter = user()

        assertEquals(1 to 1, service.vote(voter.id, id, 1).let { it.score to it.myVote })
        assertEquals(-1 to -1, service.vote(voter.id, id, -1).let { it.score to it.myVote })
        assertEquals(0 to 0, service.vote(voter.id, id, 0).let { it.score to it.myVote })
        assertFailsWith<InvalidRequestDataException> { service.vote(voter.id, id, 2) }
        assertFailsWith<BusinessRuleException> { service.vote(author.id, id, 1) }
        val revision = revisions.findLatestApproved(id)!!.id
        service.vote(voter.id, id, -1)
        assertNull(cases.findOpen(ModerationTargetType.SUBJECT_RESOURCE, revision))
        service.vote(user().id, id, -1)
        assertEquals(ModerationCaseReason.VOTES, cases.findOpen(ModerationTargetType.SUBJECT_RESOURCE, revision)?.reason)
        assertEquals(-2, links(voter).shared.single().score)
        assertEquals(-1, links(voter).shared.single().myVote)
    }

    @Test
    fun `saving and pinning accept only links the viewer sees`() {
        premoderation(false)
        val viewer = user()
        val shared = save(user(), LinkVisibility.ALL, url = "https://example.org/shared")
        val hiddenFromViewer = save(user().practice(7002), LinkVisibility.GROUP, url = "https://example.org/group")
        val own = save(viewer, LinkVisibility.PRIVATE, url = "https://example.org/own")

        assertTrue(service.setSaved(viewer.id, shared.id, true).isSaved)
        assertTrue(service.setSaved(viewer.id, shared.id, true).isSaved)
        assertTrue(links(viewer).shared.single().isSaved)
        assertFalse(service.setSaved(viewer.id, shared.id, false).isSaved)
        assertFailsWith<NotFoundException> { service.setSaved(viewer.id, hiddenFromViewer.id, true) }
        assertFailsWith<BusinessRuleException> { service.setSaved(viewer.id, own.id, true) }

        assertEquals(shared.id, service.pin(viewer.id, SUBJECT, PinSubjectLinkRequest(PERIOD, shared.id)).pinnedId)
        assertEquals(shared.id, links(viewer).pinnedId)
        assertEquals(own.id, service.pin(viewer.id, SUBJECT, PinSubjectLinkRequest(PERIOD, own.id)).pinnedId)
        assertFailsWith<NotFoundException> { service.pin(viewer.id, SUBJECT, PinSubjectLinkRequest(PERIOD, hiddenFromViewer.id)) }
        assertFailsWith<NotFoundException> { service.pin(viewer.id, 43, PinSubjectLinkRequest(PERIOD, shared.id)) }
        assertEquals(own.id, links(viewer).pinnedId)
        assertNull(service.pin(viewer.id, SUBJECT, PinSubjectLinkRequest(PERIOD)).pinnedId)
        assertNull(links(viewer).pinnedId)
    }

    @Test
    fun `deleting a link withdraws its open cases and removes it for everybody`() {
        val author = user()
        val reader = user()
        val id = save(author, LinkVisibility.ALL, url = "https://example.org/first").id
        decide(id, ModerationAction.APPROVE)
        service.setSaved(reader.id, id, true)
        service.pin(reader.id, SUBJECT, PinSubjectLinkRequest(PERIOD, id))
        save(author, LinkVisibility.ALL, url = "https://example.org/second", id = id)
        val pending = revisions.findPending(id)!!.id
        assertNotNull(cases.findOpen(ModerationTargetType.SUBJECT_RESOURCE, pending))

        assertFailsWith<PermissionDeniedException> { service.delete(reader.id, id) }
        service.delete(author.id, id)
        em.flush(); em.clear()

        assertNull(cases.findOpen(ModerationTargetType.SUBJECT_RESOURCE, pending))
        assertEquals(ModerationCaseStatus.WITHDRAWN, cases.findAll().single { it.targetId == pending }.status)
        assertFalse(links.existsById(id))
        assertTrue(links(reader).shared.isEmpty())
        assertNull(links(reader).pinnedId)
        service.delete(author.id, id)
    }

    @Test
    fun `reports target the shown revision and the queue describes it`() {
        premoderation(false)
        val author = user()
        val id = save(author, LinkVisibility.ALL, title = "Материалы").id
        val revision = revisions.findLatestApproved(id)!!.id
        val reporters = List(3) { user() }

        assertTrue(service.report(reporters[0].id, id, ModerationReportRequest(ReportReason.BROKEN, "Не открывается")).reportedByMe)
        assertFalse(links(reporters[1]).shared.single().reportedByMe)
        assertFailsWith<BusinessRuleException> { service.report(author.id, id, ModerationReportRequest(ReportReason.SPAM)) }
        reporters.drop(1).forEach { service.report(it.id, id, ModerationReportRequest(ReportReason.BROKEN)) }

        val case = moderation.cases(moderator.id, ModerationCaseStatus.OPEN).single { it.target is SubjectLinkTarget &&
            (it.target as SubjectLinkTarget).revision.id == revision }
        assertEquals(ModerationCaseReason.REPORTS, case.reason)
        val target = case.target as SubjectLinkTarget
        assertEquals(id, target.revision.linkId)
        assertEquals(1, target.revision.number)
        assertEquals(id, target.link.id)
        assertEquals(SubjectLinkStatus.PUBLISHED, target.link.status)
        assertEquals(author.isu, target.author.isu)
        assertEquals(3, target.reports.size)
        assertEquals(1, target.submitterHistory.approved)
    }

    @Test
    fun `hiding everything by an author hides published links and rejects pending revisions`() {
        val author = user().practice(7002)
        val classmate = user().practice(7002)
        val group = save(author, LinkVisibility.GROUP, url = "https://example.org/group").id
        val pending = save(author, LinkVisibility.ALL, url = "https://example.org/pending").id
        val second = save(author, LinkVisibility.ALL, url = "https://example.org/second").id
        val private = save(author, LinkVisibility.PRIVATE, url = "https://example.org/private").id
        val case = cases.findOpen(ModerationTargetType.SUBJECT_RESOURCE, revisions.findPending(pending)!!.id)!!
        val otherCase = cases.findOpen(ModerationTargetType.SUBJECT_RESOURCE, revisions.findPending(second)!!.id)!!

        moderation.decide(moderator.id, case.id, ModerationDecisionRequest(ModerationAction.HIDE_ALL_BY_USER, note = "Спам"))

        assertTrue(links(classmate).shared.isEmpty())
        val mine = links(author).mine.associateBy { it.id }
        assertEquals(SubjectLinkStatus.HIDDEN, mine.getValue(group).status)
        assertEquals(SubjectLinkStatus.REJECTED, mine.getValue(pending).status)
        assertEquals(SubjectLinkStatus.PRIVATE, mine.getValue(private).status)
        assertEquals(ModerationCaseStatus.OPEN, cases.findById(case.id).orElseThrow().status)
        assertEquals(ModerationCaseStatus.WITHDRAWN, cases.findById(otherCase.id).orElseThrow().status)
    }

    @Test
    fun `switching premoderation off approves pending public links`() {
        val reader = user()
        val id = save(user(), LinkVisibility.ALL).id
        assertTrue(links(reader).shared.isEmpty())
        premoderation(false)
        assertEquals(listOf(id), links(reader).shared.map { it.id })
    }

    private fun links(viewer: User, period: String = PERIOD): SubjectLinksResponse = service.links(viewer.id, SUBJECT, period)

    private fun save(
        owner: User,
        visibility: LinkVisibility,
        url: String = "https://example.org/${UUID.randomUUID()}",
        category: LinkCategory = LinkCategory.MATERIALS,
        title: String? = null,
        period: String = PERIOD,
        id: UUID = UUID.randomUUID(),
    ): SubjectLink = service.save(owner.id, id, SaveSubjectLinkRequest(SUBJECT, "Предмет", period, category, url, title, visibility))

    private fun decide(linkId: UUID, action: ModerationAction, note: String? = null) {
        val revision = revisions.findPending(linkId) ?: error("No pending revision")
        val case = cases.findOpen(ModerationTargetType.SUBJECT_RESOURCE, revision.id) ?: error("No open case")
        moderation.decide(moderator.id, case.id, ModerationDecisionRequest(action, note))
    }

    private fun premoderation(enabled: Boolean) = settings.update(moderator.id, ModerationSettings(
        mapOf(ModerationTargetType.SUBJECT_RESOURCE to ModerationPolicy(premoderation = enabled))))

    private fun policyDecision(revisionId: UUID): ModerationDecisionEntity = em.entityManager.createQuery(
        "SELECT d FROM ModerationDecisionEntity d WHERE d.case.targetId = :target", ModerationDecisionEntity::class.java)
        .setParameter("target", revisionId).singleResult

    private fun restrict(user: User, capability: RestrictionCapability) {
        val case = em.persist(ModerationCaseEntity(targetType = ModerationTargetType.SUBJECT_RESOURCE, targetId = UUID.randomUUID(),
            reason = ModerationCaseReason.REPORTS, openedAt = NOW))
        val decision = em.persist(ModerationDecisionEntity(case = case, moderator = moderator, action = ModerationAction.RESTRICT_USER,
            restrictionCapability = capability, createdAt = NOW))
        em.persistAndFlush(UserRestrictionEntity(user = user, capability = capability, decision = decision, reason = "Правила",
            startsAt = NOW.minusSeconds(60)))
    }

    private fun user(): User = em.persistAndFlush(User(isu = nextIsu++, name = "Synthetic user", pictureUrl = null, createdAt = NOW).apply {
        settings = UserSettingsEntity(user = this)
    })

    private fun User.practice(flowId: Long, groupName: String = "P3119", period: String = PERIOD) = flow(flowId, 2, groupName, period)
    private fun User.lecture(flowId: Long, groupName: String = "P3119", period: String = PERIOD) = flow(flowId, 1, groupName, period)

    private fun User.flow(flowId: Long, typeId: Int, groupName: String, period: String): User = also {
        flows.upsert(id, SUBJECT, period, flowId, groupName, typeId, LocalDate.parse("2026-09-07"))
    }

    private companion object {
        const val SUBJECT = 42L
        const val PERIOD = "2026-1"
        val NOW: Instant = Instant.parse("2026-09-22T09:00:00Z")
    }
}
