package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.ModerationDecisionRequest
import dev.alllexey.itmowidgets.backend.dto.ModerationSettings
import dev.alllexey.itmowidgets.backend.dto.SubjectLinkTarget
import dev.alllexey.itmowidgets.backend.dto.UserCapabilities
import dev.alllexey.itmowidgets.backend.dto.UserData
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.model.*
import dev.alllexey.itmowidgets.backend.repositories.AdminAuditRepository
import dev.alllexey.itmowidgets.backend.repositories.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.core.model.GroupData
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.bean.override.mockito.MockitoBean

@Import(AdminModerationService::class, AdminUserSummaries::class, AdminRestrictionViews::class, AdminAuditService::class,
    SubjectLinkService::class, SubjectLinkViews::class, ScheduleFlowMembership::class, UserPrivacyService::class,
    RestrictionService::class, ModerationSettingsService::class, ModerationService::class, ModerationReportService::class,
    ModerationTargets::class, ModeratorAccess::class, AdminAccess::class, AdminModerationServiceTest.TimeConfig::class)
class AdminModerationServiceTest @Autowired constructor(
    private val service: AdminModerationService,
    private val audit: AdminAuditRepository,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var friends: FriendService
    @MockitoBean private lateinit var currentGroups: CurrentStudyGroupsService

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig { @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC) }

    private var nextIsu = 959100
    private var opened = 0L
    private lateinit var moderator: User
    private lateinit var admin: User
    private lateinit var student: User

    @BeforeEach
    fun fixture() {
        moderator = user()
        admin = user()
        student = user()
        em.persist(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), NOW))
        em.persistAndFlush(UserRoleEntity(UserRoleId(admin.id, UserRole.ADMIN), NOW))
        doAnswer { it.getArgument<UserData>(0).copy(groups = listOf(GroupData("P9999", 4, "ФПИиКТ"))) }
            .`when`(currentGroups).userData(any(UserData::class.java) ?: SAMPLE)
    }

    @Test
    fun `the queue pages open cases oldest first with revision link author and active report count`() {
        val submissionAuthor = user("Автор Заявки")
        val reportedAuthor = user("Автор Жалоб")
        val submission = case(revision(submissionAuthor, LinkRevisionStatus.PENDING), ModerationCaseReason.SUBMISSION)
        val reportedRevision = revision(reportedAuthor, LinkRevisionStatus.APPROVED, hidden = true, score = -2)
        val reported = case(reportedRevision, ModerationCaseReason.REPORTS)
        report(reportedRevision, user()); report(reportedRevision, user()); report(reportedRevision, user(), dismissed = true)
        val gone = em.persist(ModerationCaseEntity(targetType = ModerationTargetType.SUBJECT_RESOURCE, targetId = UUID.randomUUID(),
            status = ModerationCaseStatus.RESOLVED, reason = ModerationCaseReason.VOTES, openedAt = NOW.minusSeconds(9000), resolvedAt = NOW))
        em.flush(); em.clear()

        val open = service.cases(moderator.id, ModerationCaseStatus.OPEN, null, 0, 20)
        assertEquals(listOf(submission.id, reported.id), open.items.map { it.id })
        assertEquals(2, open.total)
        val first = open.items[0]
        assertEquals(submission.targetId, first.revision!!.id)
        assertEquals(LinkRevisionStatus.PENDING, first.revision!!.status)
        assertEquals("Автор Заявки", first.author!!.name)
        assertEquals(submissionAuthor.isu, first.author!!.isu)
        assertEquals(0, first.reportCount)
        assertEquals("Предмет", first.link!!.subjectName)
        val second = open.items[1]
        assertEquals(2, second.reportCount)
        assertTrue(second.link!!.hidden)
        assertEquals(-2, second.link!!.score)
        assertEquals(ModerationCaseReason.REPORTS, second.reason)

        assertEquals(listOf(reported.id), service.cases(moderator.id, ModerationCaseStatus.OPEN, ModerationCaseReason.REPORTS, 0, 20).items.map { it.id })
        val page2 = service.cases(moderator.id, ModerationCaseStatus.OPEN, null, 1, 1)
        assertEquals(listOf(reported.id), page2.items.map { it.id })
        assertEquals(2, page2.total)

        val closed = service.cases(admin.id, ModerationCaseStatus.RESOLVED, ModerationCaseReason.VOTES, 0, 20).items.single { it.id == gone.id }
        assertNull(closed.revision); assertNull(closed.link); assertNull(closed.author)
        assertFailsWith<PermissionDeniedException> { service.cases(student.id, ModerationCaseStatus.OPEN, null, 0, 20) }
    }

    @Test
    fun `a queue page takes the same number of statements for one author or many`() {
        case(revision(user(), LinkRevisionStatus.PENDING), ModerationCaseReason.SUBMISSION)
        em.flush(); em.clear()
        val single = statements { service.cases(moderator.id, ModerationCaseStatus.OPEN, null, 0, 20) }
        repeat(6) {
            val revision = revision(user(), LinkRevisionStatus.APPROVED)
            case(revision, ModerationCaseReason.REPORTS)
            report(revision, user())
        }
        em.flush(); em.clear()
        var size = 0
        val many = statements { size = service.cases(moderator.id, ModerationCaseStatus.OPEN, null, 0, 20).items.size }
        assertEquals(7, size)
        assertTrue(single > 0)
        assertEquals(single, many)
    }

    @Test
    fun `case detail and decisions resolve current groups of the author for moderators only`() {
        val author = user("Синтетический Автор")
        val case = case(revision(author, LinkRevisionStatus.PENDING), ModerationCaseReason.SUBMISSION)
        em.flush(); em.clear()

        val detail = service.case(moderator.id, case.id)
        val target = assertIs<SubjectLinkTarget>(detail.target)
        assertEquals(listOf("P9999"), target.author.groups.map { it.name })
        assertEquals(listOf("P9999"), target.link.author!!.groups.map { it.name })
        assertFailsWith<PermissionDeniedException> { service.case(student.id, case.id) }

        val decided = service.decide(moderator.id, case.id, ModerationDecisionRequest(ModerationAction.APPROVE))
        assertEquals(ModerationCaseStatus.RESOLVED, decided.status)
        assertEquals(listOf("P9999"), assertIs<SubjectLinkTarget>(decided.target).author.groups.map { it.name })
    }

    @Test
    fun `restrictions filter by ISU and activity and moderators revoke them`() {
        val author = user("Нарушитель")
        val other = user("Другой")
        val case = case(revision(author, LinkRevisionStatus.APPROVED), ModerationCaseReason.REPORTS)
        val decision = em.persist(ModerationDecisionEntity(case = case, moderator = moderator, action = ModerationAction.RESTRICT_USER,
            restrictionCapability = RestrictionCapability.ALL, createdAt = NOW.minusSeconds(100)))
        val active = em.persist(UserRestrictionEntity(user = author, capability = RestrictionCapability.ALL, decision = decision,
            reason = "Спам", startsAt = NOW.minusSeconds(100)))
        em.persist(UserRestrictionEntity(user = author, capability = RestrictionCapability.VOTE, decision = decision,
            reason = "Истекло", startsAt = NOW.minusSeconds(86_400 * 3L), expiresAt = NOW.minusSeconds(86_400)))
        em.persist(UserRestrictionEntity(user = other, capability = RestrictionCapability.REPORT, decision = decision,
            reason = "Другой", startsAt = NOW.minusSeconds(50)))
        em.flush(); em.clear()

        val activeOnly = service.restrictions(moderator.id, author.isu, true, 0, 20)
        assertEquals(listOf(active.id), activeOnly.items.map { it.id })
        assertEquals("Нарушитель", activeOnly.items.single().user.name)
        assertEquals(case.id, activeOnly.items.single().caseId)
        assertNull(activeOnly.items.single().expiresAt)
        val all = service.restrictions(moderator.id, author.isu, false, 0, 20)
        assertEquals(listOf(RestrictionCapability.ALL, RestrictionCapability.VOTE), all.items.map { it.capability })
        assertEquals(listOf(true, false), all.items.map { it.active })
        val everybody = service.restrictions(moderator.id, null, true, 0, 20).items.map { it.user.isu }
        assertTrue(everybody.containsAll(listOf(author.isu, other.isu)))

        service.revokeRestriction(moderator.id, active.id)
        em.flush(); em.clear()
        val revoked = service.restrictions(moderator.id, author.isu, false, 0, 20).items.first { it.id == active.id }
        assertFalse(revoked.active)
        assertEquals(moderator.isu, revoked.revokedByIsu)
        assertEquals(NOW, revoked.revokedAt)
        assertFailsWith<PermissionDeniedException> { service.restrictions(student.id, null, true, 0, 20) }
    }

    @Test
    fun `only admins read and change the policy and each change is audited`() {
        assertFailsWith<PermissionDeniedException> { service.settings(moderator.id) }
        val next = ModerationSettings(mapOf(ModerationTargetType.SUBJECT_RESOURCE to
            ModerationPolicy(premoderation = false, reportThreshold = 5)))
        assertFailsWith<PermissionDeniedException> { service.updateSettings(moderator.id, next) }

        assertEquals(next, service.updateSettings(admin.id, next))
        assertEquals(next, service.settings(admin.id))
        service.updateSettings(admin.id, next)
        val entries = audit.findPage(PageRequest.of(0, 20)).content.filter { it.actorId == admin.id }
        assertEquals(listOf("MODERATION_SETTINGS_CHANGED"), entries.map { it.action })
        assertEquals("SUBJECT_RESOURCE.premoderation true -> false; SUBJECT_RESOURCE.report_threshold 3 -> 5", entries.single().details)
        assertEquals("moderation-settings", entries.single().target)
    }

    private fun <T> statements(block: () -> T): Long {
        val statistics = em.entityManager.entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()
        try {
            block()
            return statistics.prepareStatementCount
        } finally {
            statistics.isStatisticsEnabled = false
            em.clear()
        }
    }

    private fun user(name: String = "Synthetic user"): User = em.persist(User(isu = nextIsu++, name = name, pictureUrl = null, createdAt = NOW).apply {
        settings = UserSettingsEntity(user = this)
    })

    private fun revision(owner: User, status: LinkRevisionStatus, hidden: Boolean = false, score: Int = 0): SubjectLinkRevisionEntity {
        val url = "https://example.org/${UUID.randomUUID()}"
        val link = em.persist(SubjectLinkEntity(id = UUID.randomUUID(), owner = owner, subjectId = 42, subjectName = "Предмет",
            periodKey = "2026-1", category = LinkCategory.MATERIALS, url = url, normalizedUrl = url, title = "Материалы",
            visibility = LinkVisibility.ALL, score = score, hiddenAt = if (hidden) NOW else null, createdAt = NOW, updatedAt = NOW))
        return em.persist(SubjectLinkRevisionEntity(link = link, number = 1, category = link.category, url = url, normalizedUrl = url,
            title = link.title, visibility = link.visibility, status = status, submittedAt = NOW,
            decidedAt = if (status == LinkRevisionStatus.PENDING) null else NOW))
    }

    private fun case(revision: SubjectLinkRevisionEntity, reason: ModerationCaseReason) = em.persist(ModerationCaseEntity(
        targetType = ModerationTargetType.SUBJECT_RESOURCE, targetId = revision.id, reason = reason,
        openedAt = NOW.minusSeconds(3600 - opened++)))

    private fun report(revision: SubjectLinkRevisionEntity, reporter: User, dismissed: Boolean = false) = em.persist(ModerationReportEntity(
        targetType = ModerationTargetType.SUBJECT_RESOURCE, targetId = revision.id, reporter = reporter, reason = ReportReason.BROKEN,
        createdAt = NOW.minusSeconds(60), dismissedAt = if (dismissed) NOW else null))

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
        val SAMPLE = UserData(0, "", null, emptyList(), UserCapabilities(false, false, false))
    }
}
